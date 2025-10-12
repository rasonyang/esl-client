package org.freeswitch.esl.client.inbound;

import org.freeswitch.esl.client.internal.Context;
import org.freeswitch.esl.client.internal.IModEslApi;
import org.freeswitch.esl.client.transport.CommandResponse;
import org.freeswitch.esl.client.transport.event.EslEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A reconnectable ESL client wrapper that provides automatic reconnection on heartbeat timeout.
 * <p>
 * This client wraps the standard {@link Client} and adds:
 * <ul>
 * <li>Automatic HEARTBEAT monitoring (FreeSWITCH sends every 20 seconds)</li>
 * <li>Health check with configurable timeout threshold</li>
 * <li>Automatic reconnection with exponential backoff and jitter</li>
 * <li>Preservation of listeners and subscriptions across reconnections</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>
 * ReconnectionConfig config = new ReconnectionConfig();
 * ReconnectableClient client = new ReconnectableClient(config);
 *
 * // Add business event listeners
 * client.addEventListener((ctx, event) -> {
 *     System.out.println("Channel event: " + event.getEventName());
 * }, "CHANNEL_CREATE", "CHANNEL_HANGUP");
 *
 * // Connect (automatically subscribes to HEARTBEAT internally)
 * client.connect(new InetSocketAddress("localhost", 8021), "ClueCon", 10);
 *
 * // Set business event subscriptions
 * client.setEventSubscriptions(IModEslApi.EventFormat.PLAIN, "CHANNEL_CREATE CHANNEL_HANGUP");
 * </pre>
 */
public class ReconnectableClient {

    private static final Logger log = LoggerFactory.getLogger(ReconnectableClient.class);

    /**
     * Internal class to store listener configuration for restoration after reconnection.
     */
    private static class ListenerConfig {
        final IEslEventListener listener;
        final Set<String> eventNames; // null = global listener

        ListenerConfig(IEslEventListener listener, Set<String> eventNames) {
            this.listener = listener;
            this.eventNames = eventNames;
        }
    }

    private final Client client;
    private final HeartbeatMonitor heartbeatMonitor;
    private final ExponentialBackoffStrategy reconnectStrategy;
    private final ReconnectionConfig config;

    // Connection parameters (saved for reconnection)
    private SocketAddress address;
    private String password;
    private int timeoutSeconds;

    // Subscription and listener state (preserved across reconnections)
    private final List<ListenerConfig> userListeners = new CopyOnWriteArrayList<>();
    private volatile String eventSubscription;
    private volatile IModEslApi.EventFormat eventFormat;

    // State management
    private volatile boolean running = true;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    // Background tasks
    private final ScheduledExecutorService healthCheckExecutor;
    private ScheduledFuture<?> healthCheckTask;

    /**
     * Creates a new reconnectable client with default configuration.
     */
    public ReconnectableClient() {
        this(new ReconnectionConfig());
    }

    /**
     * Creates a new reconnectable client with custom configuration.
     *
     * @param config reconnection configuration
     */
    public ReconnectableClient(ReconnectionConfig config) {
        this.config = config;
        this.client = new Client();
        this.heartbeatMonitor = new HeartbeatMonitor(config.getHeartbeatTimeoutMs());
        this.reconnectStrategy = ExponentialBackoffStrategy.fromConfig(config);
        this.healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("health-check").factory()
        );

        log.info("ReconnectableClient created with config: {}", config);
    }

    /**
     * Connects to FreeSWITCH and starts heartbeat monitoring.
     *
     * @param address        socket address to connect to
     * @param password       ESL password
     * @param timeoutSeconds connection timeout in seconds
     * @throws InboundConnectionFailure if connection fails
     */
    public void connect(SocketAddress address, String password, int timeoutSeconds) throws InboundConnectionFailure {
        // Save connection parameters for reconnection
        this.address = address;
        this.password = password;
        this.timeoutSeconds = timeoutSeconds;

        // Perform initial connection
        doConnect();

        // Start health check
        startHealthCheck();

        log.info("ReconnectableClient connected to {}", address);
    }

    /**
     * Internal method to perform connection.
     */
    private void doConnect() throws InboundConnectionFailure {
        log.info("Connecting to {}...", address);

        // Connect the underlying client
        client.connect(address, password, timeoutSeconds);

        // Subscribe to HEARTBEAT events (in addition to user subscriptions)
        String heartbeatSubscription = "HEARTBEAT";
        if (eventSubscription != null && !eventSubscription.isEmpty()) {
            heartbeatSubscription = eventSubscription + " " + heartbeatSubscription;
        }
        client.setEventSubscriptions(
                eventFormat != null ? eventFormat : IModEslApi.EventFormat.PLAIN,
                heartbeatSubscription
        );

        // Add internal HEARTBEAT listener
        client.addEventListener((ctx, event) -> {
            if ("HEARTBEAT".equals(event.getEventName())) {
                heartbeatMonitor.recordHeartbeat();
                log.trace("Heartbeat received");
            }
        });

        // Restore user listeners
        restoreUserListeners();

        // Reset heartbeat monitor
        heartbeatMonitor.reset();

        connected.set(true);
        log.info("Connected successfully");
    }

    /**
     * Restores user listeners after reconnection.
     */
    private void restoreUserListeners() {
        for (ListenerConfig config : userListeners) {
            if (config.eventNames == null) {
                client.addEventListener(config.listener);
            } else {
                client.addEventListener(config.listener, config.eventNames.toArray(new String[0]));
            }
        }
        log.debug("Restored {} user listeners", userListeners.size());
    }

    /**
     * Starts the health check task.
     */
    private void startHealthCheck() {
        if (healthCheckTask != null && !healthCheckTask.isDone()) {
            healthCheckTask.cancel(false);
        }

        healthCheckTask = healthCheckExecutor.scheduleWithFixedDelay(
                this::performHealthCheck,
                config.getHealthCheckIntervalMs(),
                config.getHealthCheckIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        log.debug("Health check started with interval: {}ms", config.getHealthCheckIntervalMs());
    }

    /**
     * Performs health check and triggers reconnection if needed.
     */
    private void performHealthCheck() {
        try {
            if (!running) {
                return;
            }

            if (heartbeatMonitor.isTimeout() && connected.get() && !reconnecting.get()) {
                log.warn("Heartbeat timeout detected after {}ms, triggering reconnection",
                        heartbeatMonitor.getTimeSinceLastHeartbeat());
                connected.set(false);
                reconnect();
            }
        } catch (Exception e) {
            log.error("Error during health check", e);
        }
    }

    /**
     * Performs reconnection with exponential backoff.
     */
    private void reconnect() {
        if (!reconnecting.compareAndSet(false, true)) {
            log.debug("Reconnection already in progress, skipping");
            return;
        }

        log.info("Starting reconnection process");

        Thread.startVirtualThread(() -> {
            while (running && reconnecting.get()) {
                try {
                    // Clean up old connection
                    try {
                        if (client.canSend()) {
                            client.close();
                        }
                    } catch (Exception e) {
                        log.debug("Error closing old connection", e);
                    }

                    // Calculate delay with exponential backoff
                    long delay = reconnectStrategy.nextDelay();
                    log.info("Waiting {}ms before reconnection attempt {} ...",
                            delay, reconnectStrategy.getAttemptCount());
                    Thread.sleep(delay);

                    // Attempt reconnection
                    doConnect();

                    // Success
                    log.info("Reconnection successful after {} attempts", reconnectStrategy.getAttemptCount());
                    reconnectStrategy.reset();
                    reconnecting.set(false);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Reconnection interrupted");
                    reconnecting.set(false);
                    break;
                } catch (Exception e) {
                    log.error("Reconnection attempt {} failed: {}",
                            reconnectStrategy.getAttemptCount(), e.getMessage());
                    // Continue loop to retry
                }
            }
        });
    }

    /**
     * Adds a global event listener that receives all events.
     *
     * @param listener the event listener
     */
    public void addEventListener(IEslEventListener listener) {
        if (listener == null) {
            return;
        }

        userListeners.add(new ListenerConfig(listener, null));

        if (connected.get() && client.canSend()) {
            client.addEventListener(listener);
        }

        log.debug("Added global listener");
    }

    /**
     * Adds a filtered event listener that receives specific event types.
     *
     * @param listener   the event listener
     * @param eventNames event names to filter
     */
    public void addEventListener(IEslEventListener listener, String... eventNames) {
        if (listener == null || eventNames == null || eventNames.length == 0) {
            return;
        }

        Set<String> eventNameSet = new HashSet<>(Arrays.asList(eventNames));
        userListeners.add(new ListenerConfig(listener, eventNameSet));

        if (connected.get() && client.canSend()) {
            client.addEventListener(listener, eventNames);
        }

        log.debug("Added filtered listener for events: {}", Arrays.toString(eventNames));
    }

    /**
     * Removes an event listener.
     *
     * @param listener the listener to remove
     * @return true if removed
     */
    public boolean removeEventListener(IEslEventListener listener) {
        if (listener == null) {
            return false;
        }

        boolean removed = userListeners.removeIf(config -> config.listener == listener);

        if (removed && connected.get() && client.canSend()) {
            client.removeEventListener(listener);
        }

        log.debug("Removed listener: {}", removed);
        return removed;
    }

    /**
     * Sets event subscriptions.
     * This will be preserved across reconnections.
     *
     * @param format event format
     * @param events events to subscribe to
     * @return command response
     */
    public CommandResponse setEventSubscriptions(IModEslApi.EventFormat format, String events) {
        this.eventFormat = format;
        this.eventSubscription = events;

        if (connected.get() && client.canSend()) {
            // Add HEARTBEAT to user subscription
            String fullSubscription = events + " HEARTBEAT";
            return client.setEventSubscriptions(format, fullSubscription);
        }

        log.debug("Saved event subscription for later: {} {}", format, events);
        return null;
    }

    /**
     * Checks if the client can send commands.
     *
     * @return true if connected and can send
     */
    public boolean canSend() {
        return connected.get() && client.canSend();
    }

    /**
     * Checks if reconnection is in progress.
     *
     * @return true if reconnecting
     */
    public boolean isReconnecting() {
        return reconnecting.get();
    }

    /**
     * Gets the underlying client instance.
     * Use with caution - direct modifications may not be preserved across reconnections.
     *
     * @return the underlying client
     */
    public Client getUnderlyingClient() {
        return client;
    }

    /**
     * Gets the heartbeat monitor.
     *
     * @return the heartbeat monitor
     */
    public HeartbeatMonitor getHeartbeatMonitor() {
        return heartbeatMonitor;
    }

    /**
     * Closes the client and stops all background tasks.
     */
    public void close() {
        log.info("Closing ReconnectableClient");

        running = false;
        reconnecting.set(false);

        // Stop health check
        if (healthCheckTask != null) {
            healthCheckTask.cancel(false);
        }

        // Shutdown executor
        healthCheckExecutor.shutdown();
        try {
            if (!healthCheckExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                healthCheckExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            healthCheckExecutor.shutdownNow();
        }

        // Close underlying client
        try {
            if (client.canSend()) {
                client.close();
            }
        } catch (Exception e) {
            log.warn("Error closing underlying client", e);
        }

        connected.set(false);
        log.info("ReconnectableClient closed");
    }
}
