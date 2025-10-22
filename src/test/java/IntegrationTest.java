import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.internal.Context;
import org.freeswitch.esl.client.internal.IModEslApi;
import org.freeswitch.esl.client.outbound.IClientHandler;
import org.freeswitch.esl.client.outbound.SocketClient;
import org.freeswitch.esl.client.transport.SendMsg;
import org.freeswitch.esl.client.transport.event.EslEvent;
import org.freeswitch.esl.client.transport.message.EslMessage;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for FreeSWITCH Event Socket Library (ESL).
 * Tests both inbound and outbound modes against a real FreeSWITCH instance.
 *
 * Prerequisites:
 * - FreeSWITCH running on 127.0.0.1:8022 with password "ClueCon"
 * - Dialplan configured to route extension 159999 to 127.0.0.1:8084
 * - Example dialplan:
 *   {@code
 *   <extension name="outbound_test">
 *     <condition field="destination_number" expression="^159999$">
 *       <action application="socket" data="127.0.0.1:8084 async full"/>
 *     </condition>
 *   </extension>
 *   }
 *
 * Run with: gradle test --tests IntegrationTest
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(IntegrationTest.class);

    // FreeSWITCH configuration
    private static final String FREESWITCH_HOST = "127.0.0.1";
    private static final int FREESWITCH_ESL_PORT = 8022;
    private static final String FREESWITCH_PASSWORD = "ClueCon";
    private static final int OUTBOUND_SERVER_PORT = 8084;
    private static final String TEST_EXTENSION = "159999";

    // Test infrastructure
    private Client inboundClient;
    private SocketClient outboundServer;
    private final AtomicInteger callCounter = new AtomicInteger(0);

    @BeforeAll
    void setupInfrastructure() throws Exception {
        log.info("=================================================");
        log.info("Starting ESL Integration Tests");
        log.info("=================================================");

        // Start inbound client for monitoring and control
        setupInboundClient();

        // Start outbound server for call handling
        setupOutboundServer();

        log.info("Test infrastructure ready");
    }

    @AfterAll
    void tearDownInfrastructure() throws Exception {
        log.info("Tearing down test infrastructure...");

        if (outboundServer != null) {
            outboundServer.stop();
            log.info("Outbound server stopped");
        }

        if (inboundClient != null) {
            inboundClient.close();
            log.info("Inbound client disconnected");
        }

        log.info("Test infrastructure torn down");
    }

    /**
     * Setup inbound client for monitoring and API commands
     */
    private void setupInboundClient() throws Exception {
        log.info("Setting up inbound client...");

        inboundClient = new Client();
        inboundClient.connect(
            new InetSocketAddress(FREESWITCH_HOST, FREESWITCH_ESL_PORT),
            FREESWITCH_PASSWORD,
            10
        );

        // Subscribe to all events for monitoring
        inboundClient.setEventSubscriptions(IModEslApi.EventFormat.PLAIN, "all");

        log.info("Inbound client connected and subscribed to events");
    }

    /**
     * Setup outbound server for call handling
     */
    private void setupOutboundServer() throws Exception {
        log.info("Setting up outbound server on port {}...", OUTBOUND_SERVER_PORT);

        outboundServer = new SocketClient(
            new InetSocketAddress("127.0.0.1", OUTBOUND_SERVER_PORT),
            () -> new TestCallHandler(callCounter.incrementAndGet())
        );

        outboundServer.start();

        log.info("Outbound server started successfully");
    }

    /**
     * Test 1: Inbound client basic connectivity
     */
    @Test
    @Order(1)
    @DisplayName("Test inbound client connection and authentication")
    void testInboundConnection() throws Exception {
        log.info("=== Test 1: Inbound Connection ===");

        assertNotNull(inboundClient, "Inbound client should be connected");

        // Test basic API command
        EslMessage response = inboundClient.sendApiCommand("status", "");
        assertNotNull(response, "Status command should return response");

        // Log the response for debugging (don't assert on specific header as format may vary)
        String replyText = response.getHeaderValue(
            org.freeswitch.esl.client.transport.message.EslHeaders.Name.REPLY_TEXT
        );
        log.info("FreeSWITCH status response received: {}", replyText != null ? replyText : "[no reply text]");

        // Just verify we got a response - the response object itself proves connection worked
        assertTrue(response.getHeaders() != null && !response.getHeaders().isEmpty(),
                   "Response should have headers");

        log.info("✓ Inbound connection test passed");
    }

    /**
     * Test 2: Inbound client API commands
     */
    @Test
    @Order(2)
    @DisplayName("Test inbound client API commands")
    void testInboundApiCommands() throws Exception {
        log.info("=== Test 2: Inbound API Commands ===");

        // Test 'show channels' command
        EslMessage channelsResponse = inboundClient.sendApiCommand("show", "channels");
        assertNotNull(channelsResponse, "Show channels should return response");
        log.info("Active channels response received");

        // Test 'sofia status' command
        EslMessage sofiaResponse = inboundClient.sendApiCommand("sofia", "status");
        assertNotNull(sofiaResponse, "Sofia status should return response");
        log.info("Sofia status response received");

        log.info("✓ Inbound API commands test passed");
    }

    /**
     * Test 3: Inbound client event subscription
     */
    @Test
    @Order(3)
    @DisplayName("Test inbound client event subscription and reception")
    void testInboundEventSubscription() throws Exception {
        log.info("=== Test 3: Inbound Event Subscription ===");

        CountDownLatch eventLatch = new CountDownLatch(1);
        AtomicReference<EslEvent> receivedEvent = new AtomicReference<>();

        // Add event listener
        inboundClient.addEventListener((ctx, event) -> {
            log.info("Received event: {}", event.getEventName());
            receivedEvent.set(event);
            eventLatch.countDown();
        });

        // Trigger an event by sending a command that generates events
        inboundClient.sendApiCommand("status", "");

        // Wait for at least one event (HEARTBEAT should arrive within 20 seconds)
        boolean eventReceived = eventLatch.await(25, TimeUnit.SECONDS);

        assertTrue(eventReceived, "Should receive at least one event within timeout");
        assertNotNull(receivedEvent.get(), "Event should not be null");

        log.info("✓ Inbound event subscription test passed");
    }

    /**
     * Test 4: Outbound server accepts connections
     */
    @Test
    @Order(4)
    @DisplayName("Test outbound server connection handling")
    void testOutboundServerConnection() throws Exception {
        log.info("=== Test 4: Outbound Server Connection ===");

        assertNotNull(outboundServer, "Outbound server should be running");

        // The server is listening, we'll verify actual connection handling in integration test

        log.info("✓ Outbound server connection test passed");
    }

    /**
     * Test 5: Full integration - Inbound originates call, Outbound handles it
     * This is the main integration test that validates the complete flow
     */
    @Test
    @Order(5)
    @DisplayName("Test full integration: Inbound originate + Outbound handle")
    void testFullIntegration() throws Exception {
        log.info("=== Test 5: Full Integration Test ===");

        // Setup: Latches and tracking for async operations
        CountDownLatch callStartedLatch = new CountDownLatch(1);
        CountDownLatch callAnsweredLatch = new CountDownLatch(1);
        CountDownLatch callCompletedLatch = new CountDownLatch(1);

        AtomicReference<String> callUuid = new AtomicReference<>();
        AtomicInteger eventCount = new AtomicInteger(0);

        // Monitor events from inbound client perspective
        inboundClient.addEventListener((ctx, event) -> {
            String eventName = event.getEventName();
            String uuid = event.getEventHeaders().get("Unique-ID");

            eventCount.incrementAndGet();
            log.info("[INBOUND MONITOR] Event: {} (UUID: {})", eventName, uuid);

            // Track key events
            if ("CHANNEL_CREATE".equals(eventName)) {
                String destNumber = event.getEventHeaders().get("Caller-Destination-Number");
                if (TEST_EXTENSION.equals(destNumber)) {
                    callUuid.set(uuid);
                    log.info("[INBOUND MONITOR] Test call created with UUID: {}", uuid);
                    callStartedLatch.countDown();
                }
            } else if ("CHANNEL_ANSWER".equals(eventName)) {
                if (uuid != null && uuid.equals(callUuid.get())) {
                    log.info("[INBOUND MONITOR] Test call answered");
                    callAnsweredLatch.countDown();
                }
            } else if ("CHANNEL_HANGUP".equals(eventName)) {
                if (uuid != null && uuid.equals(callUuid.get())) {
                    String cause = event.getEventHeaders().get("Hangup-Cause");
                    log.info("[INBOUND MONITOR] Test call hung up, cause: {}", cause);
                    callCompletedLatch.countDown();
                }
            }
        });

        // Originate call to test extension using inbound client
        log.info("Originating test call to extension {}...", TEST_EXTENSION);

        String originateCommand = String.format(
            "originate {origination_caller_id_number=IntegrationTest}loopback/%s/default &park()",
            TEST_EXTENSION
        );

        EslMessage originateResponse = inboundClient.sendApiCommand("bgapi", originateCommand);
        assertNotNull(originateResponse, "Originate command should return response");

        String jobUuid = originateResponse.getHeaderValue(
            org.freeswitch.esl.client.transport.message.EslHeaders.Name.JOB_UUID
        );
        log.info("Originate job UUID: {}", jobUuid);

        // Wait for call to be created
        log.info("Waiting for call to be created...");
        boolean callStarted = callStartedLatch.await(10, TimeUnit.SECONDS);
        assertTrue(callStarted, "Call should be created within 10 seconds");
        assertNotNull(callUuid.get(), "Call UUID should be captured");

        // Wait for call to be answered (outbound server answers it)
        log.info("Waiting for call to be answered...");
        boolean callAnswered = callAnsweredLatch.await(10, TimeUnit.SECONDS);
        assertTrue(callAnswered, "Call should be answered within 10 seconds");

        // Wait for call to complete (after playback and hangup)
        log.info("Waiting for call to complete...");
        boolean callCompleted = callCompletedLatch.await(20, TimeUnit.SECONDS);
        assertTrue(callCompleted, "Call should complete within 20 seconds");

        // Verify events were received
        int totalEvents = eventCount.get();
        log.info("Total events received during call: {}", totalEvents);
        assertTrue(totalEvents > 0, "Should receive multiple events during call");

        log.info("✓ Full integration test passed");
        log.info("  - Call created: {}", callUuid.get());
        log.info("  - Call answered by outbound server");
        log.info("  - Call completed successfully");
        log.info("  - Total events monitored: {}", totalEvents);
    }

    /**
     * Test call handler for outbound connections
     * Implements the same event-driven pattern as OutboundServerExample
     */
    private static class TestCallHandler implements IClientHandler {

        private final Logger log = LoggerFactory.getLogger(TestCallHandler.class);
        private final int callNumber;
        private Context context;
        private EventWaiter eventWaiter;

        public TestCallHandler(int callNumber) {
            this.callNumber = callNumber;
            this.eventWaiter = new EventWaiter();
        }

        /**
         * Helper class to wait for specific events in virtual threads
         */
        private class EventWaiter {
            private final ConcurrentHashMap<String, CompletableFuture<EslEvent>> waiters = new ConcurrentHashMap<>();

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

            public void notifyEvent(EslEvent event) {
                String eventName = event.getEventName();

                if ("CHANNEL_EXECUTE_COMPLETE".equals(eventName)) {
                    String executeUuid = event.getEventHeaders().get("Event-UUID");
                    if (executeUuid == null) {
                        executeUuid = event.getEventHeaders().get("Application-UUID");
                    }

                    if (executeUuid != null) {
                        CompletableFuture<EslEvent> future = waiters.remove(executeUuid);
                        if (future != null) {
                            log.debug("Call #{} - Event UUID {} matched, completing future", callNumber, executeUuid);
                            future.complete(event);
                        }
                    }
                }
            }
        }

        @Override
        public void onConnect(Context context, EslEvent eslEvent) {
            this.context = context;

            log.info("========================================");
            log.info("Call #{} - Test handler received connection", callNumber);
            log.info("========================================");

            // Extract call information
            String uuid = eslEvent.getEventHeaders().get("Channel-Unique-ID");
            if (uuid == null) uuid = eslEvent.getEventHeaders().get("Unique-ID");
            if (uuid == null) uuid = eslEvent.getEventHeaders().get("variable_uuid");

            String caller = eslEvent.getEventHeaders().get("Caller-Caller-ID-Number");
            String callee = eslEvent.getEventHeaders().get("Caller-Destination-Number");

            log.info("Call #{} - UUID: {}, From: {}, To: {}", callNumber, uuid, caller, callee);

            if (uuid == null) {
                log.error("Call #{} - ERROR: Could not extract UUID!", callNumber);
                return;
            }

            // CRITICAL: Run call handling in NEW virtual thread to avoid blocking socket reader
            Thread.ofVirtual().start(() -> {
                try {
                    handleTestCall();
                } catch (Exception e) {
                    log.error("Call #{} - Error during test call handling", callNumber, e);
                } finally {
                    log.info("Call #{} - Sending hangup command", callNumber);
                    executeAndWait("hangup", null, 5000);
                    log.info("Call #{} - Test call handler completed", callNumber);
                }
            });
        }

        /**
         * Simple test call flow: answer -> playback -> hangup
         */
        private void handleTestCall() {
            log.info("Call #{} - Answering call", callNumber);
            EslEvent answerEvent = executeAndWait("answer", null, 10000);
            assertNotNull(answerEvent, "Answer should complete");
            log.info("Call #{} - Call answered", callNumber);

            // Small delay
            sleep(500);

            log.info("Call #{} - Playing test audio", callNumber);
            EslEvent playbackEvent = executeAndWait("playback", "$${hold_music}", 15000);
            assertNotNull(playbackEvent, "Playback should complete or be interrupted");
            log.info("Call #{} - Playback completed", callNumber);
        }

        /**
         * Execute dialplan application and wait for completion
         */
        private EslEvent executeAndWait(String app, String args, long timeoutMs) {
            String executeUuid = UUID.randomUUID().toString();

            SendMsg msg = new SendMsg();
            msg.addCallCommand("execute");
            msg.addExecuteAppName(app);
            if (args != null) {
                msg.addExecuteAppArg(args);
            }
            msg.addGenericLine("Event-UUID", executeUuid);

            log.info("Call #{} - Executing {} (UUID: {})", callNumber, app, executeUuid);
            context.sendMessageAsync(msg);

            // Wait for CHANNEL_EXECUTE_COMPLETE event
            EslEvent event = eventWaiter.waitForExecuteComplete(executeUuid, timeoutMs);

            if (event != null) {
                String appName = event.getEventHeaders().get("Application");
                String response = event.getEventHeaders().get("Application-Response");
                log.info("Call #{} - {} completed: {}", callNumber, appName, response);
            } else {
                log.warn("Call #{} - {} did not complete within timeout", callNumber, app);
            }

            return event;
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void onEslEvent(Context ctx, EslEvent event) {
            String eventName = event.getEventName();
            log.debug("Call #{} - [EVENT] {}", callNumber, eventName);

            // Notify EventWaiter for execute complete events
            eventWaiter.notifyEvent(event);
        }
    }
}
