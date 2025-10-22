import org.freeswitch.esl.client.dptools.Execute;
import org.freeswitch.esl.client.dptools.ExecuteException;
import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.internal.Context;
import org.freeswitch.esl.client.internal.IModEslApi;
import org.freeswitch.esl.client.outbound.IClientHandler;
import org.freeswitch.esl.client.outbound.SocketClient;
import org.freeswitch.esl.client.transport.event.EslEvent;
import org.freeswitch.esl.client.transport.message.EslHeaders.Name;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Complete example of FreeSWITCH Event Socket Library Outbound Server.
 *
 * This example demonstrates:
 * - Starting an outbound server to accept connections from FreeSWITCH
 * - Handling incoming calls with full IVR functionality
 * - Monitoring events via inbound client for debugging
 * - Proper shutdown and resource cleanup
 *
 * Prerequisites:
 * 1. FreeSWITCH must be running with Event Socket module enabled
 * 2. Event Socket configured on localhost:8021 with password "ClueCon"
 * 3. FreeSWITCH dialplan configured to route calls to outbound server
 *
 * Example FreeSWITCH dialplan configuration:
 * {@code
 * <extension name="outbound_test">
 *   <condition field="destination_number" expression="^159999$">
 *     <action application="socket" data="localhost:8084 async full"/>
 *   </condition>
 * </extension>
 * }
 *
 * Usage:
 * - Run directly in IDE: Right-click -> Run
 * - Command line: gradle compileTestJava && java -cp build/classes/java/main:build/classes/java/test OutboundServerExample
 *
 * Test by calling extension 159999 from any SIP client registered to FreeSWITCH.
 */
public class OutboundServerExample {

    private static final Logger logger = LoggerFactory.getLogger(OutboundServerExample.class);

    // Configuration
    private static final String FREESWITCH_HOST = "localhost";
    private static final int FREESWITCH_ESL_PORT = 8022;
    private static final String FREESWITCH_PASSWORD = "ClueCon";
    private static final int OUTBOUND_SERVER_PORT = 8084;

    // Sound file paths (adjust these to match your FreeSWITCH installation)
    private static final String SOUND_BASE = "/usr/local/freeswitch/sounds/en/us/callie/";
    private static final String WELCOME_SOUND = SOUND_BASE + "ivr/8000/ivr-welcome.wav";
    private static final String ENTER_EXTENSION_SOUND = SOUND_BASE + "ivr/8000/ivr-please_enter_extension_followed_by_pound.wav";
    private static final String INVALID_SOUND = SOUND_BASE + "ivr/8000/ivr-that_was_an_invalid_entry.wav";
    private static final String THANK_YOU_SOUND = SOUND_BASE + "ivr/8000/ivr-thank_you.wav";

    // Global state
    private static Client inboundClient;
    private static SocketClient outboundServer;
    private static final AtomicInteger callCounter = new AtomicInteger(0);

    public static void main(String[] args) {
        logger.info("=================================================");
        logger.info("FreeSWITCH Event Socket Outbound Server Example");
        logger.info("=================================================");

        // Parse command line arguments if provided
        String host = args.length > 0 ? args[0] : FREESWITCH_HOST;
        int eslPort = args.length > 1 ? Integer.parseInt(args[1]) : FREESWITCH_ESL_PORT;
        String password = args.length > 2 ? args[2] : FREESWITCH_PASSWORD;
        int outboundPort = args.length > 3 ? Integer.parseInt(args[3]) : OUTBOUND_SERVER_PORT;

        // Register shutdown hook for graceful cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received, cleaning up...");
            cleanup();
        }));

        try {
            // Start inbound client for event monitoring
            // startInboundMonitoring(host, eslPort, password);

            // Start outbound server for call handling
            startOutboundServer(outboundPort);

            logger.info("");
            logger.info("Server is ready!");
            logger.info("Call extension 159999 to test the outbound server");
            logger.info("Press Ctrl+C to stop");
            logger.info("");

            // Keep main thread alive
            Thread.currentThread().join();

        } catch (InterruptedException e) {
            logger.info("Main thread interrupted");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Fatal error in main", e);
            System.exit(1);
        }
    }

    /**
     * Start inbound client to monitor FreeSWITCH events for debugging
     */
    private static void startInboundMonitoring(String host, int port, String password) {
        try {
            logger.info("Starting inbound client for event monitoring...");

            inboundClient = new Client();
            inboundClient.connect(new InetSocketAddress(host, port), password, 10);

            // Subscribe to all events for complete visibility
            inboundClient.setEventSubscriptions(IModEslApi.EventFormat.PLAIN, "all");

            // Add event listener with detailed logging
            inboundClient.addEventListener((ctx, event) -> {
                String eventName = event.getEventName();
                String uuid = event.getEventHeaders().get("Unique-ID");

                // Log important events
                if (isImportantEvent(eventName)) {
                    logger.info("[INBOUND EVENT] {} - UUID: {}", eventName, uuid);

                    // Log additional details for specific events
                    if ("CHANNEL_CREATE".equals(eventName)) {
                        String caller = event.getEventHeaders().get("Caller-Caller-ID-Number");
                        String callee = event.getEventHeaders().get("Caller-Destination-Number");
                        logger.info("  Caller: {} -> Callee: {}", caller, callee);
                    } else if ("CHANNEL_HANGUP".equals(eventName)) {
                        String cause = event.getEventHeaders().get("Hangup-Cause");
                        logger.info("  Hangup Cause: {}", cause);
                    }
                } else {
                    // Log all other events at debug level
                    logger.debug("[INBOUND EVENT] {}", eventName);
                }
            });

            logger.info("Inbound client connected and monitoring events");

        } catch (Exception e) {
            logger.error("Failed to start inbound monitoring", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Start outbound server to handle incoming calls from FreeSWITCH
     */
    private static void startOutboundServer(int port) {
        try {
            logger.info("Starting outbound server on port {}...", port);

            outboundServer = new SocketClient(
                new InetSocketAddress("localhost", port),
                () -> new CallHandler()
            );

            outboundServer.start();

            logger.info("Outbound server started successfully");

        } catch (Exception e) {
            logger.error("Failed to start outbound server", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Check if an event is important enough to log at INFO level
     */
    private static boolean isImportantEvent(String eventName) {
        return eventName != null && (
            eventName.startsWith("CHANNEL_") ||
            eventName.equals("DETECTED_SPEECH") ||
            eventName.equals("DTMF") ||
            eventName.equals("CUSTOM")
        );
    }

    /**
     * Clean up resources on shutdown
     */
    private static void cleanup() {
        logger.info("Cleaning up resources...");

        if (outboundServer != null) {
            try {
                outboundServer.stop();
                logger.info("Outbound server stopped");
            } catch (Exception e) {
                logger.warn("Error stopping outbound server", e);
            }
        }

        if (inboundClient != null) {
            try {
                inboundClient.close();
                logger.info("Inbound client disconnected");
            } catch (Exception e) {
                logger.warn("Error closing inbound client", e);
            }
        }

        logger.info("Cleanup complete");
    }

    /**
     * Handler for each incoming call (outbound connection from FreeSWITCH)
     */
    private static class CallHandler implements IClientHandler {

        private final Logger log = LoggerFactory.getLogger(CallHandler.class);
        private final int callNumber;
        private Context context;
        private EventWaiter eventWaiter;

        public CallHandler() {
            this.callNumber = callCounter.incrementAndGet();
            this.eventWaiter = new EventWaiter();
        }

        /**
         * Helper class to wait for specific events in virtual threads
         */
        private class EventWaiter {
            private final ConcurrentHashMap<String, CompletableFuture<EslEvent>> waiters = new ConcurrentHashMap<>();

            /**
             * Wait for a specific Application-UUID to complete
             */
            public EslEvent waitForExecuteComplete(String executeUuid, long timeoutMs) {
                CompletableFuture<EslEvent> future = new CompletableFuture<>();
                waiters.put(executeUuid, future);

                try {
                    return future.get(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    waiters.remove(executeUuid);
                    log.warn("Call #{} - Timeout waiting for execute completion: {}", callNumber, executeUuid);
                    return null;
                } catch (InterruptedException | ExecutionException e) {
                    waiters.remove(executeUuid);
                    log.error("Call #{} - Error waiting for event", callNumber, e);
                    return null;
                }
            }

            /**
             * Notify when an event arrives
             */
            public void notifyEvent(EslEvent event) {
                String eventName = event.getEventName();

                if ("CHANNEL_EXECUTE_COMPLETE".equals(eventName)) {
                    // Try Event-UUID first (what we set), then Application-UUID (FreeSWITCH default)
                    String executeUuid = event.getEventHeaders().get("Event-UUID");
                    if (executeUuid == null) {
                        executeUuid = event.getEventHeaders().get("Application-UUID");
                    }

                    if (executeUuid != null) {
                        CompletableFuture<EslEvent> future = waiters.remove(executeUuid);
                        if (future != null) {
                            log.info("Call #{} - Event UUID {} matched, completing future", callNumber, executeUuid);
                            future.complete(event);
                        } else {
                            log.debug("Call #{} - Event UUID {} not found in waiters", callNumber, executeUuid);
                        }
                    } else {
                        log.warn("Call #{} - CHANNEL_EXECUTE_COMPLETE without Event-UUID or Application-UUID", callNumber);
                    }
                }
            }
        }

        @Override
        public void onConnect(Context context, EslEvent eslEvent) {
            this.context = context;  // Store for async operations

            log.info("========================================");
            log.info("Call #{} - New outbound connection received", callNumber);
            log.info("========================================");

            // Extract call information
            // Try multiple possible UUID field names
            String uuid = eslEvent.getEventHeaders().get("Channel-Unique-ID");
            if (uuid == null) {
                uuid = eslEvent.getEventHeaders().get("Unique-ID");
            }
            if (uuid == null) {
                uuid = eslEvent.getEventHeaders().get("variable_uuid");
            }

            String caller = eslEvent.getEventHeaders().get("Caller-Caller-ID-Number");
            String callee = eslEvent.getEventHeaders().get("Caller-Destination-Number");

            log.info("Call #{} - UUID: {}", callNumber, uuid);
            log.info("Call #{} - From: {} To: {}", callNumber, caller, callee);

            // Check if UUID was found
            if (uuid == null) {
                log.error("Call #{} - ERROR: Could not extract UUID from event headers!", callNumber);
                log.error("Call #{} - Available headers: {}", callNumber, eslEvent.getEventHeaders().keySet());
                return;
            }

            // Log all event headers for debugging (optional)
            if (log.isDebugEnabled()) {
                log.debug("Call #{} - Event details:\n{}", callNumber,
                    formatEventDetails(eslEvent.getMessageHeaders(), eslEvent.getEventBodyLines()));
            }

            // In async full mode, FreeSWITCH automatically sends all channel events
            // No need to subscribe to myevents
            log.info("Call #{} - Starting call handling (async full mode - events auto-sent)", callNumber);

            // IMPORTANT: Must run call handling in a separate virtual thread!
            // Reason: onConnect() runs in the channel's single-threaded eventExecutor.
            // If we block here waiting for events, the eventExecutor cannot process
            // incoming events (like CHANNEL_EXECUTE_COMPLETE), causing deadlock.
            // Solution: Start a new virtual thread for call handling logic.
            Thread.ofVirtual().start(() -> {
                try {
                    // Main call handling logic using event-driven sync style
                    handleCall(caller, callee);

                } catch (Exception e) {
                    log.error("Call #{} - Error during call execution", callNumber, e);
                } finally {
                    // Send hangup command
                    log.info("Call #{} - Sending hangup command", callNumber);
                    executeAndWait("hangup", null, 5000);
                    log.info("Call #{} - Call handler completed", callNumber);
                }
            });
        }

        /**
         * Main IVR call flow using event-driven sync style
         * Looks like sync code, but actually waits for events in virtual threads
         */
        private void handleCall(String caller, String callee) {
            log.info("Call #{} - Answering call", callNumber);
            executeAndWait("answer", null, 30000);
            log.info("Call #{} - Call answered", callNumber);

            // Small delay to ensure audio path is ready
            sleep(500);

            log.info("Call #{} - Playing welcome message", callNumber);
            executeAndWait("playback", "$${hold_music}", 60000);
            log.info("Call #{} - Playback completed", callNumber);
        }

        /**
         * Execute a dialplan application and wait for it to complete
         * This blocks the virtual thread until CHANNEL_EXECUTE_COMPLETE event arrives
         */
        private void executeAndWait(String app, String args, long timeoutMs) {
            String executeUuid = UUID.randomUUID().toString();

            org.freeswitch.esl.client.transport.SendMsg msg =
                new org.freeswitch.esl.client.transport.SendMsg();
            msg.addCallCommand("execute");
            msg.addExecuteAppName(app);
            if (args != null) {
                msg.addExecuteAppArg(args);
            }
            msg.addGenericLine("Event-UUID", executeUuid);

            log.info("Call #{} - Executing {} (UUID: {})", callNumber, app, executeUuid);
            context.sendMessageAsync(msg);

            // Wait for CHANNEL_EXECUTE_COMPLETE event (blocks virtual thread)
            EslEvent event = eventWaiter.waitForExecuteComplete(executeUuid, timeoutMs);

            if (event != null) {
                String appName = event.getEventHeaders().get("Application");
                String response = event.getEventHeaders().get("Application-Response");
                log.info("Call #{} - {} completed: {}", callNumber, appName, response);
            } else {
                log.warn("Call #{} - {} did not complete within timeout", callNumber, app);
            }
        }

        /**
         * Helper for interruptible sleep
         */
        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Call #{} - Sleep interrupted", callNumber);
            }
        }

        /**
         * Process the collected extension
         */
        private void processExtension(Execute exe, String extension) throws ExecuteException {
            log.info("Call #{} - Processing extension: {}", callNumber, extension);

            // Example: different actions based on extension
            switch (extension) {
                case "1000":
                case "1001":
                case "1002":
                    log.info("Call #{} - Valid extension, would bridge to {}", callNumber, extension);
                    // In real scenario: exe.bridge("user/" + extension);
                    break;

                case "159999":
                    log.info("Call #{} - Echo test extension", callNumber);
                    // exe.echo(); // Would start echo test
                    break;

                default:
                    log.info("Call #{} - Unknown extension", callNumber);
                    exe.playback(INVALID_SOUND);
                    break;
            }
        }

        @Override
        public void onEslEvent(Context ctx, EslEvent event) {
            String eventName = event.getEventName();
            log.info("Call #{} - [EVENT] {}", callNumber, eventName);

            // Notify EventWaiter for execute complete events
            eventWaiter.notifyEvent(event);

            // Handle specific events if needed
            if ("DTMF".equals(eventName)) {
                String digit = event.getEventHeaders().get("DTMF-Digit");
                log.info("Call #{} - DTMF received: {}", callNumber, digit);
            }

            // Log execution-related events
            if ("CHANNEL_EXECUTE".equals(eventName) || "CHANNEL_EXECUTE_COMPLETE".equals(eventName)) {
                String app = event.getEventHeaders().get("Application");
                String appUuid = event.getEventHeaders().get("Application-UUID");
                log.info("Call #{} - {} - App: {}, UUID: {}", callNumber, eventName, app, appUuid);
            }
        }

        /**
         * Format event details for logging
         */
        private String formatEventDetails(Map<Name, String> headers, List<String> lines) {
            StringBuilder sb = new StringBuilder("\nHeaders:\n");
            for (Name key : headers.keySet()) {
                if (key == null) continue;
                sb.append("  ").append(key.toString())
                  .append(" = ")
                  .append(headers.get(key))
                  .append("\n");
            }
            if (lines != null && !lines.isEmpty()) {
                sb.append("Body Lines:\n");
                for (String line : lines) {
                    sb.append("  ").append(line).append("\n");
                }
            }
            return sb.toString();
        }
    }
}
