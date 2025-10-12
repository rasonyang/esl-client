import org.freeswitch.esl.client.dptools.Execute;
import org.freeswitch.esl.client.dptools.ExecuteException;
import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.internal.Context;
import org.freeswitch.esl.client.outbound.IClientHandler;
import org.freeswitch.esl.client.outbound.SocketClient;
import org.freeswitch.esl.client.transport.event.EslEvent;
import org.freeswitch.esl.client.transport.message.EslHeaders.Name;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for outbound ESL server using Java 21 virtual threads.
 * Tests both inbound monitoring and outbound call handling.
 */
@Tag("integration")
class OutboundTest {
    private static final Logger logger = LoggerFactory.getLogger(OutboundTest.class);
    private static final String SOUND_BASE = "/usr/local/freeswitch/sounds/en/us/callie/ivr/8000/";
    private static final String PROMPT = SOUND_BASE + "ivr-please_enter_extension_followed_by_pound.wav";
    private static final String FAILED = SOUND_BASE + "ivr-that_was_an_invalid_entry.wav";

    private Client inboundClient;
    private SocketClient outboundServer;

    @BeforeEach
    void setUp() {
        // Initialized in tests as needed
    }

    @AfterEach
    void tearDown() {
        if (inboundClient != null) {
            try {
                inboundClient.close();
            } catch (Exception e) {
                logger.warn("Error closing inbound client", e);
            }
        }
        if (outboundServer != null) {
            try {
                outboundServer.stop();
            } catch (Exception e) {
                logger.warn("Error stopping outbound server", e);
            }
        }
    }

    @Test
    @Disabled("Requires FreeSWITCH running on localhost:8021 and incoming calls")
    void testOutboundServerWithInboundMonitoring() throws Exception {
        logger.info("Starting outbound server integration test");

        // Counter for received events
        AtomicInteger inboundEventCount = new AtomicInteger(0);
        AtomicInteger outboundConnectionCount = new AtomicInteger(0);
        CountDownLatch connectionLatch = new CountDownLatch(1);

        // Setup inbound client for monitoring
        inboundClient = new Client();
        inboundClient.connect(new InetSocketAddress("localhost", 8021), "ClueCon", 10);
        inboundClient.addEventListener((ctx, event) -> {
            inboundEventCount.incrementAndGet();
            logger.info("INBOUND onEslEvent: {}", event.getEventName());
        });

        // Setup outbound server (uses virtual threads internally)
        outboundServer = new SocketClient(
                new InetSocketAddress("localhost", 8084),
                () -> new IClientHandler() {
                    @Override
                    public void onConnect(Context context, EslEvent eslEvent) {
                        outboundConnectionCount.incrementAndGet();
                        connectionLatch.countDown();

                        logger.info("Outbound connection received");
                        logger.debug(formatEventDetails(eslEvent.getMessageHeaders(),
                                                       eslEvent.getEventBodyLines()));

                        String uuid = eslEvent.getEventHeaders().get("unique-id");
                        logger.info("Creating execute app for uuid {}", uuid);

                        Execute exe = new Execute(context, uuid);

                        try {
                            exe.answer();

                            String digits = exe.playAndGetDigits(3, 5, 10, 10 * 1000,
                                    "#", PROMPT, FAILED, "^\\d+", 10 * 1000);
                            logger.info("Digits collected: {}", digits);

                        } catch (ExecuteException e) {
                            logger.error("Could not prompt for digits", e);
                        } finally {
                            try {
                                exe.hangup(null);
                            } catch (ExecuteException e) {
                                logger.error("Could not hangup", e);
                            }
                        }
                    }

                    @Override
                    public void onEslEvent(Context ctx, EslEvent event) {
                        logger.info("OUTBOUND onEslEvent: {}", event.getEventName());
                    }
                });

        // Start the outbound server
        outboundServer.start();
        logger.info("Outbound server started on port 8084, waiting for connections...");

        // Wait for connection (or timeout)
        boolean receivedConnection = connectionLatch.await(30, TimeUnit.SECONDS);

        logger.info("Test completed - Inbound events: {}, Outbound connections: {}",
                inboundEventCount.get(), outboundConnectionCount.get());

        // Note: This test will timeout if no calls are made
        // In real scenarios, you would trigger a call from FreeSWITCH dialplan
    }

    @Test
    void testOutboundServerCanBeCreated() throws Exception {
        // Unit test that doesn't require FreeSWITCH
        outboundServer = new SocketClient(
                new InetSocketAddress("localhost", 9084), // Different port
                () -> new IClientHandler() {
                    @Override
                    public void onConnect(Context context, EslEvent eslEvent) {
                        logger.info("Handler created");
                    }

                    @Override
                    public void onEslEvent(Context ctx, EslEvent event) {
                        logger.info("Event received");
                    }
                });

        assertNotNull(outboundServer, "Outbound server should be instantiated");

        // Start and immediately stop
        outboundServer.start();
        Thread.sleep(100); // Give it time to start
        outboundServer.stop();

        logger.info("Outbound server lifecycle test passed");
    }

    @Test
    void testEventDetailsFormatter() {
        // Unit test for the helper method
        Map<Name, String> headers = Map.of(
                Name.CONTENT_TYPE, "text/event-plain",
                Name.CONTENT_LENGTH, "100"
        );
        List<String> lines = List.of("Line 1", "Line 2");

        String result = formatEventDetails(headers, lines);

        assertNotNull(result);
        assertTrue(result.contains("Headers:"));
        assertTrue(result.contains("Body Lines:"));
        assertTrue(result.contains("Line 1"));
        logger.info("Event formatter test passed");
    }

    /**
     * Formats event headers and body lines for logging.
     */
    private static String formatEventDetails(Map<Name, String> headers, List<String> lines) {
        StringBuilder sb = new StringBuilder("\nHeaders:\n");
        for (Name key : headers.keySet()) {
            if (key == null) continue;
            sb.append(key.toString());
            sb.append("\n\t\t\t\t = \t ");
            sb.append(headers.get(key));
            sb.append("\n");
        }
        if (lines != null) {
            sb.append("Body Lines:\n");
            for (String line : lines) {
                sb.append(line);
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
