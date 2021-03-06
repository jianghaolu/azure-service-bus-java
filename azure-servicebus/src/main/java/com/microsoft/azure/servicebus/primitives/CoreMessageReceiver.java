/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */
package com.microsoft.azure.servicebus.primitives;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.messaging.Source;
import org.apache.qpid.proton.amqp.messaging.Target;
import org.apache.qpid.proton.amqp.transport.DeliveryState;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.amqp.transport.ReceiverSettleMode;
import org.apache.qpid.proton.amqp.transport.SenderSettleMode;
import org.apache.qpid.proton.engine.BaseHandler;
import org.apache.qpid.proton.engine.Connection;
import org.apache.qpid.proton.engine.Delivery;
import org.apache.qpid.proton.engine.EndpointState;
import org.apache.qpid.proton.engine.Receiver;
import org.apache.qpid.proton.engine.Session;
import org.apache.qpid.proton.message.Message;
import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.UnsignedInteger;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.Modified;
import org.apache.qpid.proton.amqp.messaging.Outcome;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.amqp.messaging.Released;

import com.microsoft.azure.servicebus.amqp.AmqpConstants;
import com.microsoft.azure.servicebus.amqp.DispatchHandler;
import com.microsoft.azure.servicebus.amqp.IAmqpReceiver;
import com.microsoft.azure.servicebus.amqp.ReceiveLinkHandler;
import com.microsoft.azure.servicebus.amqp.SessionHandler;
import com.microsoft.azure.servicebus.rules.RuleDescription;

/**
 * Common Receiver that abstracts all amqp related details
 * translates event-driven reactor model into async receive Api
 */

// TODO Take a re-look at the choice of collections used. Some of them are overkill may be.
public class CoreMessageReceiver extends ClientEntity implements IAmqpReceiver, IErrorContextProvider
{
	private static final Logger TRACE_LOGGER = Logger.getLogger(ClientConstants.SERVICEBUS_CLIENT_TRACE);
	
	private final Object requestResonseLinkCreationLock = new Object();
	private final List<ReceiveWorkItem> pendingReceives;
	private final ConcurrentHashMap<String, UpdateStateWorkItem> pendingUpdateStateRequests;
	private final ConcurrentHashMap<String, Delivery> tagsToDeliveriesMap;
	private final MessagingFactory underlyingFactory;
	private final String receivePath;
	private final Duration operationTimeout;
	private final CompletableFuture<Void> linkClose;
	private final Object prefetchCountSync;
	private final SettleModePair settleModePair;
	private final RetryPolicy retryPolicy;
	private int prefetchCount;
	private String sessionId;
	private boolean isSessionReceiver;
	private boolean isBrowsableSession;
	private Instant sessionLockedUntilUtc;

	private ConcurrentLinkedQueue<MessageWithDeliveryTag> prefetchedMessages;
	private Receiver receiveLink;
	private RequestResponseLink requestResponseLink;
	private WorkItem<CoreMessageReceiver> linkOpen;
	private Duration factoryRceiveTimeout;

	private Exception lastKnownLinkError;
	private Instant lastKnownErrorReportedAt;
	private int nextCreditToFlow;
		
	private final Runnable timedOutUpdateStateRequestsDaemon;
	
	// Change onReceiveComplete to handle empty deliveries. Change onError to retry updateState requests.
	

	private CoreMessageReceiver(final MessagingFactory factory,
			final String name, 
			final String recvPath,
			final String sessionId,
			final int prefetchCount,
			final SettleModePair settleModePair)
	{
		super(name, factory);

		this.underlyingFactory = factory;
		this.operationTimeout = factory.getOperationTimeout();
		this.receivePath = recvPath;
		this.sessionId = sessionId;
		this.isSessionReceiver = false;
		this.isBrowsableSession = false;
		this.prefetchCount = prefetchCount;
		this.settleModePair = settleModePair;
		this.prefetchedMessages = new ConcurrentLinkedQueue<MessageWithDeliveryTag>();
		this.linkClose = new CompletableFuture<Void>();
		this.lastKnownLinkError = null;
		this.factoryRceiveTimeout = factory.getOperationTimeout();
		this.prefetchCountSync = new Object();
		this.retryPolicy = factory.getRetryPolicy();

		this.pendingReceives = Collections.synchronizedList(new LinkedList<ReceiveWorkItem>());
		this.pendingUpdateStateRequests = new ConcurrentHashMap<>();
		this.tagsToDeliveriesMap = new ConcurrentHashMap<>();
		this.lastKnownErrorReportedAt = Instant.now();
		
		this.timedOutUpdateStateRequestsDaemon = new Runnable() {
			@Override
			public void run() {
				for(Map.Entry<String, UpdateStateWorkItem> entry : CoreMessageReceiver.this.pendingUpdateStateRequests.entrySet())
				{
					Duration remainingTime = entry.getValue().getTimeoutTracker().remaining();
					if(remainingTime.isZero() || remainingTime.isNegative())
					{
						CoreMessageReceiver.this.pendingUpdateStateRequests.remove(entry.getKey());
						Exception exception = entry.getValue().getLastKnownException();
						if(exception == null)
						{
							exception = new TimeoutException("Request timed out.");
						}
						entry.getValue().getWork().completeExceptionally(exception);
					}
				}
			}			
		};
		
		// As all update state requests have the same timeout, one timer is better than having one timer per request
		Timer.schedule(timedOutUpdateStateRequestsDaemon, Duration.ofSeconds(1), TimerType.RepeatRun);
	}

	// Connection has to be associated with Reactor before Creating a receiver on it.
	public static CompletableFuture<CoreMessageReceiver> create(
			final MessagingFactory factory, 
			final String name, 
			final String recvPath,
			final int prefetchCount,
			final SettleModePair settleModePair)
	{
		CoreMessageReceiver msgReceiver = new CoreMessageReceiver(
				factory,
				name, 
				recvPath,
				null,
				prefetchCount,
				settleModePair);
		return msgReceiver.createLink();
	}
	
	public static CompletableFuture<CoreMessageReceiver> create(
			final MessagingFactory factory, 
			final String name, 
			final String recvPath,
			final String sessionId,
			final boolean isBrowsableSession,
			final int prefetchCount,
			final SettleModePair settleModePair)
	{
		CoreMessageReceiver msgReceiver = new CoreMessageReceiver(
				factory,
				name, 
				recvPath,
				sessionId,
				prefetchCount,
				settleModePair);
		msgReceiver.isSessionReceiver = true;
		msgReceiver.isBrowsableSession = isBrowsableSession;
		return msgReceiver.createLink();
	}

	private CompletableFuture<CoreMessageReceiver> createLink()
	{
		this.linkOpen = new WorkItem<CoreMessageReceiver>(new CompletableFuture<CoreMessageReceiver>(), this.operationTimeout);
		this.scheduleLinkOpenTimeout(this.linkOpen.getTimeoutTracker());		
		try
		{
			this.underlyingFactory.scheduleOnReactorThread(new DispatchHandler()
			{
				@Override
				public void onEvent()
				{
					CoreMessageReceiver.this.createReceiveLink();
				}
			});
		}
		catch (IOException ioException)
		{
			this.linkOpen.getWork().completeExceptionally(new ServiceBusException(false, "Failed to create Receiver, see cause for more details.", ioException));
		}
		
		return this.linkOpen.getWork();		
	}
	
	private CompletableFuture<Void> createRequestResponseLink()
	{
		synchronized (this.requestResonseLinkCreationLock)
		{
			if(this.requestResponseLink == null)
			{
				String requestResponseLinkPath = RequestResponseLink.getRequestResponseLinkPath(this.receivePath);
				CompletableFuture<Void> crateAndAssignRequestResponseLink =
								RequestResponseLink.createAsync(this.underlyingFactory, this.getClientId() + "-RequestResponse", requestResponseLinkPath).thenAccept((rrlink) -> {this.requestResponseLink = rrlink;});
				return crateAndAssignRequestResponseLink;
			}
			else
			{
				return CompletableFuture.completedFuture(null);
			}
		}				
	}
	
	private void createReceiveLink()
	{	
		Connection connection = this.underlyingFactory.getConnection();	

		final Session session = connection.session();
		session.setIncomingCapacity(Integer.MAX_VALUE);
		session.open();
		BaseHandler.setHandler(session, new SessionHandler(this.receivePath));

		final String receiveLinkNamePrefix = StringUtil.getShortRandomString();
		final String receiveLinkName = !StringUtil.isNullOrEmpty(connection.getRemoteContainer()) ? 
				receiveLinkNamePrefix.concat(TrackingUtil.TRACKING_ID_TOKEN_SEPARATOR).concat(connection.getRemoteContainer()) :
				receiveLinkNamePrefix;
		final Receiver receiver = session.receiver(receiveLinkName);
		
		Source source = new Source();
		source.setAddress(receivePath);
		Map linkProperties = new HashMap();
		linkProperties.put(ClientConstants.LINK_TIMEOUT_PROPERTY, Util.adjustServerTimeout(this.underlyingFactory.getOperationTimeout()).toMillis());		
		
		if(this.isSessionReceiver)
		{
			HashMap filterMap = new HashMap();
			filterMap.put(ClientConstants.SESSION_FILTER, this.sessionId);
			source.setFilter(filterMap);
			
			linkProperties.put(ClientConstants.LINK_PEEKMODE_PROPERTY, this.isBrowsableSession);
		}		
		
		receiver.setSource(source);
		receiver.setTarget(new Target());

		// Set settle modes
		receiver.setSenderSettleMode(this.settleModePair.getSenderSettleMode());
		receiver.setReceiverSettleMode(this.settleModePair.getReceiverSettleMode());
		
		receiver.setProperties(linkProperties);

		final ReceiveLinkHandler handler = new ReceiveLinkHandler(this);
		BaseHandler.setHandler(receiver, handler);
		this.underlyingFactory.registerForConnectionError(receiver);

		receiver.open();		

		if (this.receiveLink != null)
		{			
			this.underlyingFactory.deregisterForConnectionError(this.receiveLink);
		}

		this.receiveLink = receiver;
	}

	private List<MessageWithDeliveryTag> receiveCore(final int messageCount)
	{
		List<MessageWithDeliveryTag> returnMessages = null;
		MessageWithDeliveryTag currentMessage = this.pollPrefetchQueue();
	
		while (currentMessage != null) 
		{
			if (returnMessages == null)
			{
				returnMessages = new ArrayList<MessageWithDeliveryTag>();
			}

			returnMessages.add(currentMessage);
			if (returnMessages.size() >= messageCount)
			{
				break;
			}

			currentMessage = this.pollPrefetchQueue();
		}
		
		return returnMessages;
	}

	public int getPrefetchCount()
	{
		synchronized (this.prefetchCountSync)
		{
			return this.prefetchCount;
		}
	}
	

	public String getSessionId()
	{
		return this.sessionId;
	}
	

	public Instant getSessionLockedUntilUtc()
	{
		if(this.isSessionReceiver)
		{
			return this.sessionLockedUntilUtc;
		}
		else
		{
			throw new RuntimeException("Object is not a session receiver");
		}		
	}

	public void setPrefetchCount(final int value) throws ServiceBusException
	{
		final int deltaPrefetchCount;
		synchronized (this.prefetchCountSync)
		{
			deltaPrefetchCount = this.prefetchCount - value;
			this.prefetchCount = value;
		}
		
		try
		{
			this.underlyingFactory.scheduleOnReactorThread(new DispatchHandler()
			{
				@Override
				public void onEvent()
				{
					sendFlow(deltaPrefetchCount);
				}
			});
		}
		catch (IOException ioException)
		{
			throw new ServiceBusException(false, "Setting prefetch count failed, see cause for more details", ioException);
		}
	}
	
	public CompletableFuture<Collection<MessageWithDeliveryTag>> receiveAsync(final int maxMessageCount)
	{
		return this.receiveAsync(maxMessageCount, this.factoryRceiveTimeout);
	}

	public CompletableFuture<Collection<MessageWithDeliveryTag>> receiveAsync(final int maxMessageCount, Duration timeout)
	{
		this.throwIfClosed(this.lastKnownLinkError);

		if (maxMessageCount <= 0 || maxMessageCount > this.prefetchCount)
		{
			throw new IllegalArgumentException(String.format(Locale.US, "parameter 'maxMessageCount' should be a positive number and should be less than prefetchCount(%s)", this.prefetchCount));
		}		

		CompletableFuture<Collection<MessageWithDeliveryTag>> onReceive = new CompletableFuture<Collection<MessageWithDeliveryTag>>();
		
		try
		{
			this.underlyingFactory.scheduleOnReactorThread(new DispatchHandler()
			{
				@Override
				public void onEvent()
				{
					CoreMessageReceiver.this.ensureLinkIsOpen();

					final List<MessageWithDeliveryTag> messages = receiveCore(maxMessageCount);
					if (messages != null)
					{						
						AsyncUtil.completeFuture(onReceive, messages);
					}
					else
					{
						final ReceiveWorkItem receiveWorkItem = new ReceiveWorkItem(onReceive, timeout, maxMessageCount);
						Timer.schedule(
								new Runnable()
								{
									public void run()
									{										
										if( CoreMessageReceiver.this.pendingReceives.remove(receiveWorkItem))
										{										
											// TODO: can we do it better?
											// workaround to push the sendflow-performative to reactor
											// this sets the receiveLink endpoint to modified state
											// (and increment the unsentCredits in proton by 0)
											try
											{
												CoreMessageReceiver.this.underlyingFactory.scheduleOnReactorThread(new DispatchHandler()
												{
													@Override
													public void onEvent()
													{
														//TODO: not working
														// Make credit 0, to stop further receiving on this link
														//MessageReceiver.this.receiveLink.flow(-1 * MessageReceiver.this.receiveLink.getCredit());
														CoreMessageReceiver.this.receiveLink.flow(0);
														
														// See if detach stops
//														MessageReceiver.this.receiveLink.detach();
//														MessageReceiver.this.receiveLink.close();
//														MessageReceiver.this.underlyingFactory.deregisterForConnectionError(MessageReceiver.this.receiveLink);
													}
												});
											}
											catch (IOException ignore)
											{
											}
											
											receiveWorkItem.getWork().complete(null);
										}										
									}
								},
								timeout,
								TimerType.OneTimeRun);
						pendingReceives.add(receiveWorkItem);
					}
						
				}
			});
		}
		catch (IOException ioException)
		{
			onReceive.completeExceptionally(generateDispatacherSchedulingFailedException("Receive", ioException));	
		}

		return onReceive;
	}

	@Override
	public void onOpenComplete(Exception exception)
	{		
		if (exception == null)
		{
			if(this.isSessionReceiver)
			{
				Map remoteSourceFilter = ((Source)this.receiveLink.getRemoteSource()).getFilter();
				if(remoteSourceFilter != null && remoteSourceFilter.containsKey(ClientConstants.SESSION_FILTER))
				{
					String remoteSessionId = (String)remoteSourceFilter.get(ClientConstants.SESSION_FILTER);
					this.sessionId = remoteSessionId;
					
					if(this.receiveLink.getRemoteProperties() != null && this.receiveLink.getRemoteProperties().containsKey(ClientConstants.LOCKED_UNTIL_UTC))
					{
						this.sessionLockedUntilUtc = Util.convertDotNetTicksToInstant((long)this.receiveLink.getRemoteProperties().get(ClientConstants.LOCKED_UNTIL_UTC));
					}
					else
					{
						this.sessionLockedUntilUtc = Instant.ofEpochMilli(0);
					}					
				}
				else
				{
					exception = new ServiceBusException(false, "SessionId filter not set on the remote source.");					
				}			
			}
		}		
		
		if (exception == null)
		{			
			if (this.linkOpen != null && !this.linkOpen.getWork().isDone())
			{				
				AsyncUtil.completeFuture(this.linkOpen.getWork(), this);
			}

			this.lastKnownLinkError = null;
			
			this.underlyingFactory.getRetryPolicy().resetRetryCount(this.underlyingFactory.getClientId());

			this.nextCreditToFlow = 0;
			this.sendFlow(this.prefetchCount - this.prefetchedMessages.size());

			if(TRACE_LOGGER.isLoggable(Level.FINE))
			{
				TRACE_LOGGER.log(Level.FINE, String.format("receiverPath[%s], linkname[%s], updated-link-credit[%s], sentCredits[%s]",
						this.receivePath, this.receiveLink.getName(), this.receiveLink.getCredit(), this.prefetchCount));
			}
		}
		else
		{
			if (this.linkOpen != null && !this.linkOpen.getWork().isDone())
			{
				this.setClosed();
				ExceptionUtil.completeExceptionally(this.linkOpen.getWork(), exception, this, true);
			}

			this.lastKnownLinkError = exception;
		}
	}

	@Override
	public void onReceiveComplete(Delivery delivery)
	{		
		byte[] deliveryTag = delivery.getTag();
		String deliveryTagAsString  = StringUtil.convertBytesToString(delivery.getTag());
		if(deliveryTag == null || deliveryTag.length == 0 || !this.tagsToDeliveriesMap.containsKey(deliveryTagAsString))
		{			
			Message message = null;
			
			int msgSize = delivery.pending();
			byte[] buffer = new byte[msgSize];
			
			int read = receiveLink.recv(buffer, 0, msgSize);
			
			message = Proton.message();
			message.decode(buffer, 0, read);
			
			if(this.settleModePair.getSenderSettleMode() == SenderSettleMode.SETTLED)
			{
				// No op. Delivery comes settled from the sender
				delivery.disposition(Accepted.getInstance());
				delivery.settle();
			}
			else
			{
				this.tagsToDeliveriesMap.put(StringUtil.convertBytesToString(delivery.getTag()), delivery);
				receiveLink.advance();
			}

			this.prefetchedMessages.add(new MessageWithDeliveryTag(message, delivery.getTag()));
			this.underlyingFactory.getRetryPolicy().resetRetryCount(this.getClientId());
			
			if(!this.pendingReceives.isEmpty())
			{
				final ReceiveWorkItem currentReceive = this.pendingReceives.remove(0);
				if (currentReceive != null && !currentReceive.getWork().isDone())
				{
					currentReceive.cancelTimeoutTask(false);
					
					List<MessageWithDeliveryTag> messages = this.receiveCore(currentReceive.getMaxMessageCount());

					CompletableFuture<Collection<MessageWithDeliveryTag>> future = currentReceive.getWork();					
					AsyncUtil.completeFuture(future, messages);
				}
			}
		}
		else
		{
			DeliveryState remoteState = delivery.getRemoteState();
			if(remoteState instanceof Outcome)
			{
				Outcome remoteOutcome = (Outcome)remoteState;
				UpdateStateWorkItem matchingUpdateStateWorkItem = this.pendingUpdateStateRequests.get(deliveryTagAsString);
				if(matchingUpdateStateWorkItem != null)
				{
					// This comparison is ugly. Using it for the lack of equals operation on Outcome classes
					if(remoteOutcome.getClass().getName().equals(matchingUpdateStateWorkItem.outcome.getClass().getName()))
					{
						this.completePendingUpdateStateWorkItem(delivery, deliveryTagAsString, matchingUpdateStateWorkItem, null);						
					}
					else
					{
//						if(matchingUpdateStateWorkItem.expectedOutcome instanceof Accepted)
//						{
							// Complete requests
							if(remoteOutcome instanceof Rejected)
							{
								Rejected rejected = (Rejected) remoteOutcome;
								ErrorCondition error = rejected.getError();
								Exception exception = ExceptionUtil.toException(error);

								if (ExceptionUtil.isGeneralError(error.getCondition()))
								{
									this.lastKnownLinkError = exception;
									this.lastKnownErrorReportedAt = Instant.now();
								}

								Duration retryInterval = this.retryPolicy.getNextRetryInterval(this.getClientId(), exception, matchingUpdateStateWorkItem.getTimeoutTracker().remaining());
								if (retryInterval == null)
								{									
									this.completePendingUpdateStateWorkItem(delivery, deliveryTagAsString, matchingUpdateStateWorkItem, exception);
								}
								else
								{
									matchingUpdateStateWorkItem.setLastKnownException(exception);
									// Retry after retry interval
									try
									{
										this.underlyingFactory.scheduleOnReactorThread((int) retryInterval.toMillis(),
												new DispatchHandler()
												{
													@Override
													public void onEvent()
													{
														delivery.disposition((DeliveryState)matchingUpdateStateWorkItem.getOutcome());
													}
												});
									}
									catch (IOException ioException)
									{
										this.completePendingUpdateStateWorkItem(delivery, deliveryTagAsString, matchingUpdateStateWorkItem,
												new ServiceBusException(false, "Operation failed while scheduling a retry on Reactor, see cause for more details.", ioException));
									}
								}
							}
							else if (remoteOutcome instanceof Released)
							{
								this.completePendingUpdateStateWorkItem(delivery, deliveryTagAsString, matchingUpdateStateWorkItem, new OperationCancelledException(remoteOutcome.toString()));									
							}
							else 
							{
								this.completePendingUpdateStateWorkItem(delivery, deliveryTagAsString, matchingUpdateStateWorkItem, new ServiceBusException(false, remoteOutcome.toString()));									
							}							
//						}
					}
				}
				else
				{
					// Should not happen. Ignore it
				}				
			}
			else
			{
				//Ignore it. we are only interested in terminal delivery states
			}			
		}				
	}

	public void onError(ErrorCondition error)
	{		
		Exception completionException = ExceptionUtil.toException(error);
		this.onError(completionException);
	}

	@Override
	public void onError(Exception exception)
	{
		this.prefetchedMessages.clear();

		if (this.getIsClosingOrClosed())
		{
			AsyncUtil.completeFuture(this.linkClose, null);
			this.clearAllPendingWorkItems(exception);
		}
		else
		{
			this.lastKnownLinkError = exception;
			this.onOpenComplete(exception);
			
			if (exception != null &&
					(!(exception instanceof ServiceBusException) || !((ServiceBusException) exception).getIsTransient()))
			{
				this.clearAllPendingWorkItems(exception);				
			}
			else
			{				
				// TODO change it. Why recreating link needs to wait for retry interval of pending receive?
				ReceiveWorkItem workItem = null;
				if(!this.pendingReceives.isEmpty())
				{
					workItem = this.pendingReceives.get(0);
				}
				
				if (workItem != null && workItem.getTimeoutTracker() != null)
				{
					Duration nextRetryInterval = this.underlyingFactory.getRetryPolicy()
							.getNextRetryInterval(this.getClientId(), exception, workItem.getTimeoutTracker().remaining());
					if (nextRetryInterval != null)
					{
						try
						{
							this.underlyingFactory.scheduleOnReactorThread((int) nextRetryInterval.toMillis(), new DispatchHandler()
							{
								@Override
								public void onEvent()
								{
									if (receiveLink.getLocalState() == EndpointState.CLOSED || receiveLink.getRemoteState() == EndpointState.CLOSED)
									{
										createReceiveLink();
									}
								}
							});
						}
						catch (IOException ignore)
						{
						}
					}
				}
			}
		}
	}	

	// CONTRACT: message should be delivered to the caller of MessageReceiver.receive() only via Poll on prefetchqueue
	private MessageWithDeliveryTag pollPrefetchQueue()
	{
		final MessageWithDeliveryTag message = this.prefetchedMessages.poll();
		if (message != null)
		{			
			this.sendFlow(1);
		}

		return message;
	}

	private void sendFlow(final int credits)
	{
		if(!this.isBrowsableSession)
		{
			// slow down sending the flow - to make the protocol less-chat'y
			this.nextCreditToFlow += credits;
			if (this.nextCreditToFlow >= this.prefetchCount || this.nextCreditToFlow >= 100)
			{
				final int tempFlow = this.nextCreditToFlow;
				this.receiveLink.flow(tempFlow);
				this.nextCreditToFlow = 0;
				
				if(TRACE_LOGGER.isLoggable(Level.FINE))
				{
					TRACE_LOGGER.log(Level.FINE, String.format("receiverPath[%s], linkname[%s], updated-link-credit[%s], sentCredits[%s]",
							this.receivePath, this.receiveLink.getName(), this.receiveLink.getCredit(), tempFlow));
				}
			}
		}		
	}

	private void scheduleLinkOpenTimeout(final TimeoutTracker timeout)
	{
		// timer to signal a timeout if exceeds the operationTimeout on MessagingFactory
		Timer.schedule(
				new Runnable()
				{
					public void run()
					{
						if (!linkOpen.getWork().isDone())
						{
							Exception operationTimedout = new TimeoutException(
									String.format(Locale.US, "%s operation on ReceiveLink(%s) to path(%s) timed out at %s.", "Open", CoreMessageReceiver.this.receiveLink.getName(), CoreMessageReceiver.this.receivePath, ZonedDateTime.now()),
									CoreMessageReceiver.this.lastKnownLinkError);
							if (TRACE_LOGGER.isLoggable(Level.WARNING))
							{
								TRACE_LOGGER.log(Level.WARNING, 
										String.format(Locale.US, "receiverPath[%s], linkName[%s], %s call timedout", CoreMessageReceiver.this.receivePath, CoreMessageReceiver.this.receiveLink.getName(),  "Open"), 
										operationTimedout);
							}

							ExceptionUtil.completeExceptionally(linkOpen.getWork(), operationTimedout, CoreMessageReceiver.this, false);
						}
					}
				}
				, timeout.remaining()
				, TimerType.OneTimeRun);
	}

	private void scheduleLinkCloseTimeout(final TimeoutTracker timeout)
	{
		// timer to signal a timeout if exceeds the operationTimeout on MessagingFactory
		Timer.schedule(
				new Runnable()
				{
					public void run()
					{
						if (!linkClose.isDone())
						{
							Exception operationTimedout = new TimeoutException(String.format(Locale.US, "%s operation on Receive Link(%s) timed out at %s", "Close", CoreMessageReceiver.this.receiveLink.getName(), ZonedDateTime.now()));
							if (TRACE_LOGGER.isLoggable(Level.WARNING))
							{
								TRACE_LOGGER.log(Level.WARNING, 
										String.format(Locale.US, "receiverPath[%s], linkName[%s], %s call timedout", CoreMessageReceiver.this.receivePath, CoreMessageReceiver.this.receiveLink.getName(), "Close"), 
										operationTimedout);
							}

							ExceptionUtil.completeExceptionally(linkClose, operationTimedout, CoreMessageReceiver.this, false);
						}
					}
				}
				, timeout.remaining()
				, TimerType.OneTimeRun);
	}

	@Override
	public void onClose(ErrorCondition condition)
	{
		if (condition == null)
		{
			this.onError(new ServiceBusException(true, 
					String.format(Locale.US, "Closing the link. LinkName(%s), EntityPath(%s)", this.receiveLink.getName(), this.receivePath)));
		}
		else
		{
			this.onError(condition);
		}
	}

	@Override
	public ErrorContext getContext()
	{
		final boolean isLinkOpened = this.linkOpen != null && this.linkOpen.getWork().isDone();
		final String referenceId = this.receiveLink != null && this.receiveLink.getRemoteProperties() != null && this.receiveLink.getRemoteProperties().containsKey(ClientConstants.TRACKING_ID_PROPERTY)
				? this.receiveLink.getRemoteProperties().get(ClientConstants.TRACKING_ID_PROPERTY).toString()
						: ((this.receiveLink != null) ? this.receiveLink.getName(): null);

		ReceiverErrorContext errorContext = new ReceiverErrorContext(this.underlyingFactory != null ? this.underlyingFactory.getHostName() : null,
				this.receivePath,
				referenceId,				 
						isLinkOpened ? this.prefetchCount : null, 
								isLinkOpened && this.receiveLink != null ? this.receiveLink.getCredit(): null, 
										isLinkOpened && this.prefetchedMessages != null ? this.prefetchedMessages.size(): null);

		return errorContext;
	}	

	@Override
	protected CompletableFuture<Void> onClose()
	{
		if (!this.getIsClosed())
		{
			if (this.receiveLink != null && this.receiveLink.getLocalState() != EndpointState.CLOSED)
			{
				this.receiveLink.close();
				this.underlyingFactory.deregisterForConnectionError(this.receiveLink);
				this.scheduleLinkCloseTimeout(TimeoutTracker.create(this.operationTimeout));
			}
			else
			{				
				AsyncUtil.completeFuture(this.linkClose, null);
			}
		}

		return this.linkClose.thenCompose((v) -> {
			return this.requestResponseLink == null ? CompletableFuture.completedFuture(null) : this.requestResponseLink.closeAsync();});
	}
	
	public CompletableFuture<Void> completeMessageAsync(byte[] deliveryTag)
	{		
		Outcome outcome = Accepted.getInstance();
		return this.updateMessageStateAsync(deliveryTag, outcome);
	}
	
	public CompletableFuture<Void> completeMessageAsync(UUID lockToken)
	{		
		return this.updateDispositionAsync(new UUID[]{lockToken}, ClientConstants.DISPOSITION_STATUS_COMPLETED, null, null, null);
	}
	
	public CompletableFuture<Void> abandonMessageAsync(byte[] deliveryTag, Map<String, Object> propertiesToModify)
	{		
		Modified outcome = new Modified();
		if(propertiesToModify != null)
		{
			outcome.setMessageAnnotations(propertiesToModify);
		}		
		return this.updateMessageStateAsync(deliveryTag, outcome);
	}
	
	public CompletableFuture<Void> abandonMessageAsync(UUID lockToken, Map<String, Object> propertiesToModify)
	{
		return this.updateDispositionAsync(new UUID[]{lockToken}, ClientConstants.DISPOSITION_STATUS_ABANDONED, null, null, propertiesToModify);
	}
	
	public CompletableFuture<Void> deferMessageAsync(byte[] deliveryTag, Map<String, Object> propertiesToModify)
	{		
		Modified outcome = new Modified();
		outcome.setUndeliverableHere(true);
		if(propertiesToModify != null)
		{
			outcome.setMessageAnnotations(propertiesToModify);
		}
		return this.updateMessageStateAsync(deliveryTag, outcome);
	}
	
	public CompletableFuture<Void> deferMessageAsync(UUID lockToken, Map<String, Object> propertiesToModify)
	{		
		return this.updateDispositionAsync(new UUID[]{lockToken}, ClientConstants.DISPOSITION_STATUS_DEFERED, null, null, propertiesToModify);
	}
	
	public CompletableFuture<Void> deadLetterMessageAsync(byte[] deliveryTag, String deadLetterReason, String deadLetterErrorDescription, Map<String, Object> propertiesToModify)
	{
		Rejected outcome = new Rejected();
		ErrorCondition error = new ErrorCondition(ClientConstants.DEADLETTERNAME, null);		
		Map<String, Object> errorInfo = new HashMap<String, Object>();
		if(!StringUtil.isNullOrEmpty(deadLetterReason))
		{
			errorInfo.put(ClientConstants.DEADLETTER_REASON_HEADER, deadLetterReason);
		}
		if(!StringUtil.isNullOrEmpty(deadLetterErrorDescription))
		{
			errorInfo.put(ClientConstants.DEADLETTER_ERROR_DESCRIPTION_HEADER, deadLetterErrorDescription);
		}
		if(propertiesToModify != null)
		{
			errorInfo.putAll(propertiesToModify);
		}
		error.setInfo(errorInfo);
		outcome.setError(error);
		
		return this.updateMessageStateAsync(deliveryTag, outcome);
	}
	
	public CompletableFuture<Void> deadLetterMessageAsync(UUID lockToken, String deadLetterReason, String deadLetterErrorDescription, Map<String, Object> propertiesToModify)
	{
		return this.updateDispositionAsync(new UUID[]{lockToken}, ClientConstants.DISPOSITION_STATUS_SUSPENDED, deadLetterReason, deadLetterErrorDescription, propertiesToModify);
	}
	
	private CompletableFuture<Void> updateMessageStateAsync(byte[] deliveryTag, Outcome outcome)
	{
		this.throwIfClosed(this.lastKnownLinkError);		
		CompletableFuture<Void> completeMessageFuture = new CompletableFuture<Void>();
		
		try
		{
			this.underlyingFactory.scheduleOnReactorThread(new DispatchHandler()
			{
				@Override
				public void onEvent()
				{
					CoreMessageReceiver.this.ensureLinkIsOpen();
					
					String deliveryTagAsString = StringUtil.convertBytesToString(deliveryTag);
					Delivery delivery = CoreMessageReceiver.this.tagsToDeliveriesMap.get(deliveryTagAsString);
					if(delivery == null)
					{						
						AsyncUtil.completeFutureExceptionally(completeMessageFuture, generateDeliveryNotFoundException());
					}
					else
					{
						final UpdateStateWorkItem workItem = new UpdateStateWorkItem(completeMessageFuture, outcome, CoreMessageReceiver.this.factoryRceiveTimeout);
						CoreMessageReceiver.this.pendingUpdateStateRequests.put(deliveryTagAsString, workItem);
						delivery.disposition((DeliveryState)outcome);
					}						
				}
			});
		}
		catch (IOException ioException)
		{
			completeMessageFuture.completeExceptionally(generateDispatacherSchedulingFailedException("completeMessage", ioException));					
		}

		return completeMessageFuture;
	}
	
	private void ensureLinkIsOpen()
	{		
		if (this.receiveLink.getLocalState() == EndpointState.CLOSED || this.receiveLink.getRemoteState() == EndpointState.CLOSED)
		{
			this.createReceiveLink();
		}
	}
	
	private void completePendingUpdateStateWorkItem(Delivery delivery, String deliveryTagAsString, UpdateStateWorkItem workItem, Exception exception)
	{
		delivery.settle();
		if(exception == null)
		{			
			AsyncUtil.completeFuture(workItem.getWork(), null);
		}
		else
		{
			ExceptionUtil.completeExceptionally(workItem.getWork(), exception, this, true);
		}	
		
		this.tagsToDeliveriesMap.remove(deliveryTagAsString);
		this.pendingUpdateStateRequests.remove(deliveryTagAsString);
	}
	
	private void clearAllPendingWorkItems(Exception exception)
	{
		final boolean isTransientException = exception == null ||
				(exception instanceof ServiceBusException && ((ServiceBusException) exception).getIsTransient());
		
		Iterator<ReceiveWorkItem> pendingRecivesIterator = this.pendingReceives.iterator();
		while(pendingRecivesIterator.hasNext())
		{
			ReceiveWorkItem workItem = pendingRecivesIterator.next();
			pendingRecivesIterator.remove();
			
			CompletableFuture<Collection<MessageWithDeliveryTag>> future = workItem.getWork();
			if (isTransientException)
			{				
				AsyncUtil.completeFuture(future, null);
			}
			else
			{
				ExceptionUtil.completeExceptionally(future, exception, this, true);
			}
			
			workItem.cancelTimeoutTask(false);
		}
		
		for(Map.Entry<String, UpdateStateWorkItem> pendingUpdate : this.pendingUpdateStateRequests.entrySet())
		{
			pendingUpdateStateRequests.remove(pendingUpdate.getKey());			
			ExceptionUtil.completeExceptionally(pendingUpdate.getValue().getWork(), exception, this, true);
		}		
		
		this.tagsToDeliveriesMap.clear();		
	}
	
	private static IllegalArgumentException generateDeliveryNotFoundException()
	{
		return new IllegalArgumentException("Delivery not found on the receive link.");
	}
	
	private static ServiceBusException generateDispatacherSchedulingFailedException(String operation, Exception cause)
	{
		return new ServiceBusException(false, operation + " failed while dispatching to Reactor, see cause for more details.", cause);
	}
	
	public CompletableFuture<Collection<Instant>> renewMessageLocksAsync(UUID[] lockTokens)
	{
		return this.createRequestResponseLink().thenComposeAsync((v) -> {
			HashMap requestBodyMap = new HashMap();
			requestBodyMap.put(ClientConstants.REQUEST_RESPONSE_LOCKTOKENS, lockTokens);
			if(this.isSessionReceiver)
			{
				requestBodyMap.put(ClientConstants.REQUEST_RESPONSE_SESSIONID, this.getSessionId());
			}
			
			Message requestMessage = RequestResponseUtils.createRequestMessage(ClientConstants.REQUEST_RESPONSE_RENEWLOCK_OPERATION, requestBodyMap, Util.adjustServerTimeout(this.operationTimeout));
			CompletableFuture<Message> responseFuture = this.requestResponseLink.requestAysnc(requestMessage, this.operationTimeout);
			return responseFuture.thenComposeAsync((responseMessage) -> {
				CompletableFuture<Collection<Instant>> returningFuture = new CompletableFuture<Collection<Instant>>();
				int statusCode = RequestResponseUtils.getResponseStatusCode(responseMessage);
				if(statusCode == ClientConstants.REQUEST_RESPONSE_OK_STATUS_CODE)
				{
					Date[] expirations = (Date[])RequestResponseUtils.getResponseBody(responseMessage).get(ClientConstants.REQUEST_RESPONSE_EXPIRATIONS);
					returningFuture.complete(Arrays.stream(expirations).map((d) -> d.toInstant()).collect(Collectors.toList()));
				}
				else
				{
					// error response
					returningFuture.completeExceptionally(RequestResponseUtils.genereateExceptionFromResponse(responseMessage));
				}
				return returningFuture;
			});
		});					
	}
	
	public CompletableFuture<Collection<MessageWithLockToken>> receiveBySequenceNumbersAsync(Long[] sequenceNumbers)
	{
		return this.createRequestResponseLink().thenComposeAsync((v) -> {
			HashMap requestBodyMap = new HashMap();
			requestBodyMap.put(ClientConstants.REQUEST_RESPONSE_SEQUENCE_NUMBERS, sequenceNumbers);
			requestBodyMap.put(ClientConstants.REQUEST_RESPONSE_RECEIVER_SETTLE_MODE, UnsignedInteger.valueOf(this.settleModePair.getReceiverSettleMode() == ReceiverSettleMode.FIRST ? 0 : 1));		
			if(this.isSessionReceiver)
			{
				requestBodyMap.put(ClientConstants.REQUEST_RESPONSE_SESSIONID, this.getSessionId());
			}
			
			Message requestMessage = RequestResponseUtils.createRequestMessage(ClientConstants.REQUEST_RESPONSE_RECEIVE_BY_SEQUENCE_NUMBER, requestBodyMap, Util.adjustServerTimeout(this.operationTimeout));
			CompletableFuture<Message> responseFuture = this.requestResponseLink.requestAysnc(requestMessage, this.operationTimeout);
			return responseFuture.thenComposeAsync((responseMessage) -> {
				CompletableFuture<Collection<MessageWithLockToken>> returningFuture = new CompletableFuture<Collection<MessageWithLockToken>>();
				int statusCode = RequestResponseUtils.getResponseStatusCode(responseMessage);
				if(statusCode == ClientConstants.REQUEST_RESPONSE_OK_STATUS_CODE)
				{
					List<MessageWithLockToken> receivedMessages = new ArrayList<MessageWithLockToken>();
					Object responseBodyMap = ((AmqpValue)responseMessage.getBody()).getValue();
					if(responseBodyMap != null && responseBodyMap instanceof Map)
					{					
						Object messages = ((Map)responseBodyMap).get(ClientConstants.REQUEST_RESPONSE_MESSAGES);
						if(messages != null && messages instanceof Iterable)
						{
							for(Object message : (Iterable)messages)
							{
								if(message instanceof Map)
								{
									Message receivedMessage = Message.Factory.create();
									Binary messagePayLoad = (Binary)((Map)message).get(ClientConstants.REQUEST_RESPONSE_MESSAGE);
									receivedMessage.decode(messagePayLoad.getArray(), messagePayLoad.getArrayOffset(), messagePayLoad.getLength());								
									UUID lockToken = ClientConstants.ZEROLOCKTOKEN;
									if(((Map)message).containsKey(ClientConstants.REQUEST_RESPONSE_LOCKTOKEN))
									{
										lockToken = (UUID)((Map)message).get(ClientConstants.REQUEST_RESPONSE_LOCKTOKEN);									
									}								
																	
									receivedMessages.add(new MessageWithLockToken(receivedMessage, lockToken));
								}
							}
						}
					}				
					returningFuture.complete(receivedMessages);
				}
				else
				{
					// error response
					returningFuture.completeExceptionally(RequestResponseUtils.genereateExceptionFromResponse(responseMessage));
				}
				return returningFuture;
			});
		});		
	}
	
	public CompletableFuture<Void> updateDispositionAsync(UUID[] lockTokens, String dispositionStatus, String deadLetterReason, String deadLetterErrorDescription, Map<String, Object> propertiesToModify)
	{
		return this.createRequestResponseLink().thenComposeAsync((v) -> {
			HashMap requestBodyMap = new HashMap();
			requestBodyMap.put(ClientConstants.REQUEST_RESPONSE_LOCKTOKENS, lockTokens);
			requestBodyMap.put(ClientConstants.REQUEST_RESPONSE_DISPOSITION_STATUS, dispositionStatus);
			
			if(deadLetterReason != null)
			{
				requestBodyMap.put(ClientConstants.REQUEST_RESPONSE_DEADLETTER_REASON, deadLetterReason);
			}
			
			if(deadLetterErrorDescription != null)
			{
				requestBodyMap.put(ClientConstants.REQUEST_RESPONSE_DEADLETTER_DESCRIPTION, deadLetterErrorDescription);
			}
			
			if(propertiesToModify != null && propertiesToModify.size() > 0)
			{
				requestBodyMap.put(ClientConstants.REQUEST_RESPONSE_PROPERTIES_TO_MODIFY, propertiesToModify);
			}
			
			if(this.isSessionReceiver)
			{
				requestBodyMap.put(ClientConstants.REQUEST_RESPONSE_SESSIONID, this.getSessionId());
			}
			
			Message requestMessage = RequestResponseUtils.createRequestMessage(ClientConstants.REQUEST_RESPONSE_UPDATE_DISPOSTION_OPERATION, requestBodyMap, Util.adjustServerTimeout(this.operationTimeout));
			CompletableFuture<Message> responseFuture = this.requestResponseLink.requestAysnc(requestMessage, this.operationTimeout);
			return responseFuture.thenComposeAsync((responseMessage) -> {
				CompletableFuture<Void> returningFuture = new CompletableFuture<Void>();
				int statusCode = RequestResponseUtils.getResponseStatusCode(responseMessage);
				if(statusCode == ClientConstants.REQUEST_RESPONSE_OK_STATUS_CODE)
				{
					returningFuture.complete(null);
				}
				else
				{
					// error response
					returningFuture.completeExceptionally(RequestResponseUtils.genereateExceptionFromResponse(responseMessage));
				}
				return returningFuture;
			});
		});		
	}
	
	public CompletableFuture<Void> renewSessionLocksAsync()
	{
		return this.createRequestResponseLink().thenComposeAsync((v) -> {
			HashMap requestBodyMap = new HashMap();
			requestBodyMap.put(ClientConstants.REQUEST_RESPONSE_SESSIONID, this.getSessionId());
			
			Message requestMessage = RequestResponseUtils.createRequestMessage(ClientConstants.REQUEST_RESPONSE_RENEW_SESSIONLOCK_OPERATION, requestBodyMap, Util.adjustServerTimeout(this.operationTimeout));
			CompletableFuture<Message> responseFuture = this.requestResponseLink.requestAysnc(requestMessage, this.operationTimeout);
			return responseFuture.thenComposeAsync((responseMessage) -> {
				CompletableFuture<Void> returningFuture = new CompletableFuture<Void>();
				int statusCode = RequestResponseUtils.getResponseStatusCode(responseMessage);
				if(statusCode == ClientConstants.REQUEST_RESPONSE_OK_STATUS_CODE)
				{
					Date expiration = (Date)RequestResponseUtils.getResponseBody(responseMessage).get(ClientConstants.REQUEST_RESPONSE_EXPIRATION);
					this.sessionLockedUntilUtc = expiration.toInstant();
					returningFuture.complete(null);
				}
				else
				{
					// error response
					returningFuture.completeExceptionally(RequestResponseUtils.genereateExceptionFromResponse(responseMessage));
				}
				return returningFuture;
			});
		});		
	}
	
	public CompletableFuture<byte[]> getSessionStateAsync()
	{
		return this.createRequestResponseLink().thenComposeAsync((v) -> {
			HashMap requestBodyMap = new HashMap();
			requestBodyMap.put(ClientConstants.REQUEST_RESPONSE_SESSIONID, this.getSessionId());		
			
			Message requestMessage = RequestResponseUtils.createRequestMessage(ClientConstants.REQUEST_RESPONSE_GET_SESSION_STATE_OPERATION, requestBodyMap, Util.adjustServerTimeout(this.operationTimeout));
			CompletableFuture<Message> responseFuture = this.requestResponseLink.requestAysnc(requestMessage, this.operationTimeout);
			return responseFuture.thenComposeAsync((responseMessage) -> {
				CompletableFuture<byte[]> returningFuture = new CompletableFuture<byte[]>();
				int statusCode = RequestResponseUtils.getResponseStatusCode(responseMessage);
				if(statusCode == ClientConstants.REQUEST_RESPONSE_OK_STATUS_CODE)
				{
					byte[] receivedState = null;
					Map bodyMap = RequestResponseUtils.getResponseBody(responseMessage);
					if(bodyMap.containsKey(ClientConstants.REQUEST_RESPONSE_SESSION_STATE))
					{
						Object sessionState = bodyMap.get(ClientConstants.REQUEST_RESPONSE_SESSION_STATE);
						if(sessionState != null)
						{
							receivedState = ((Binary)sessionState).getArray();
						}					
					}
					
					returningFuture.complete(receivedState);
				}
				else
				{
					// error response
					returningFuture.completeExceptionally(RequestResponseUtils.genereateExceptionFromResponse(responseMessage));
				}
				return returningFuture;
			});
		});		
	}
	
	// NULL session state is allowed
	public CompletableFuture<Void> setSessionStateAsync(byte[] sessionState)
	{
		return this.createRequestResponseLink().thenComposeAsync((v) -> {
			HashMap requestBodyMap = new HashMap();
			requestBodyMap.put(ClientConstants.REQUEST_RESPONSE_SESSIONID, this.getSessionId());
			requestBodyMap.put(ClientConstants.REQUEST_RESPONSE_SESSION_STATE, sessionState == null ? null : new Binary(sessionState));
			
			Message requestMessage = RequestResponseUtils.createRequestMessage(ClientConstants.REQUEST_RESPONSE_SET_SESSION_STATE_OPERATION, requestBodyMap, Util.adjustServerTimeout(this.operationTimeout));
			CompletableFuture<Message> responseFuture = this.requestResponseLink.requestAysnc(requestMessage, this.operationTimeout);
			return responseFuture.thenComposeAsync((responseMessage) -> {
				CompletableFuture<Void> returningFuture = new CompletableFuture<Void>();
				int statusCode = RequestResponseUtils.getResponseStatusCode(responseMessage);
				if(statusCode == ClientConstants.REQUEST_RESPONSE_OK_STATUS_CODE)
				{
					returningFuture.complete(null);				
				}
				else
				{
					// error response
					returningFuture.completeExceptionally(RequestResponseUtils.genereateExceptionFromResponse(responseMessage));
				}
				return returningFuture;
			});
		});		
	}
	
	// A receiver can be used to peek messages from any session-id, useful for browsable sessions
	public CompletableFuture<Collection<Message>> peekMessagesAsync(long fromSequenceNumber, int messageCount, String sessionId)
	{
		return this.createRequestResponseLink().thenComposeAsync((v) -> {
			return MessageBrowserUtil.peekMessagesAsync(this.requestResponseLink, this.operationTimeout, fromSequenceNumber, messageCount, sessionId);
		});		
	}	
}
