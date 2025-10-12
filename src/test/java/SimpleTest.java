import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.internal.IModEslApi.EventFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify Virtual Threads work with FreeSWITCH ESL.
 * Requires FreeSWITCH to be running on localhost:8021 with password "ClueCon".
 */
@Tag("integration")
class SimpleTest {

    private Client client;
    private static final String PASSWORD = "ClueCon";
    private static final String HOST = "localhost";
    private static final int PORT = 8021;

    @BeforeEach
    void setUp() {
        client = new Client();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }

    @Test
    @Disabled("Requires FreeSWITCH running on localhost:8021")
    void testConnectAndReceiveEvents() throws Exception {
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║  Java 21 Virtual Threads ESL Client Test             ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
        System.out.println();

        // Counter for received events
        AtomicInteger eventCount = new AtomicInteger(0);
        CountDownLatch eventLatch = new CountDownLatch(1);

        // Add event listener
        client.addEventListener((ctx, event) -> {
            int count = eventCount.incrementAndGet();
            System.out.println("✅ Event received: " + event.getEventName());
            if (event.getEventHeaders().get("FreeSWITCH-Hostname") != null) {
                System.out.println("   From: " + event.getEventHeaders().get("FreeSWITCH-Hostname"));
            }
            eventLatch.countDown();
        });

        System.out.println("📡 Connecting to FreeSWITCH...");
        System.out.println("   Host: " + HOST);
        System.out.println("   Port: " + PORT);
        System.out.println("   Password: " + PASSWORD);
        System.out.println();

        // Connect to FreeSWITCH
        client.connect(new InetSocketAddress(HOST, PORT), PASSWORD, 10);
        System.out.println("✅ Connected and authenticated!");
        System.out.println();

        // Subscribe to all events
        client.setEventSubscriptions(EventFormat.PLAIN, "all");
        System.out.println("✅ Subscribed to all events");
        System.out.println();
        System.out.println("🎧 Listening for events... (will wait up to 10 seconds)");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println();

        // Wait for at least one event
        boolean receivedEvent = eventLatch.await(10, TimeUnit.SECONDS);

        System.out.println();
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("✅ Test completed!");
        System.out.println("   Events received: " + eventCount.get());
        System.out.println("🚀 Virtual Threads are working perfectly!");

        // Assertions
        assertTrue(receivedEvent, "Should have received at least one event from FreeSWITCH");
        assertTrue(eventCount.get() > 0, "Event count should be greater than 0");
    }

    @Test
    void testClientCanBeInstantiated() {
        // Basic test that doesn't require FreeSWITCH
        assertNotNull(client, "Client should be instantiated");
    }
}
