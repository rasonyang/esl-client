package org.freeswitch.esl.client;

import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.internal.IModEslApi.EventFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating ESL client usage with Java 21 virtual threads.
 * Tests connecting to FreeSWITCH and subscribing to events.
 */
@Tag("integration")
class ClientExampleTest {
    private static final Logger L = LoggerFactory.getLogger(ClientExampleTest.class);

    private Client client;
    private static final String PASSWORD = "ClueCon";
    private static final String HOST = "localhost";
    private static final int PORT = 8021;
    private static final int TIMEOUT_SECONDS = 10;

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
                L.warn("Error closing client", e);
            }
        }
    }

    @Test
    @Disabled("Requires FreeSWITCH running on localhost:8021")
    void testConnectAndSubscribeToEvents() throws Exception {
        L.info("Starting ESL client integration test");

        // Latch to wait for first event
        CountDownLatch eventLatch = new CountDownLatch(1);
        AtomicReference<String> eventName = new AtomicReference<>();
        AtomicReference<String> hostname = new AtomicReference<>();

        // Add event listener
        client.addEventListener((ctx, event) -> {
            eventName.set(event.getEventName());
            hostname.set(event.getEventHeaders().get("FreeSWITCH-Hostname"));
            L.info("Received event: {} from {}", eventName.get(), hostname.get());
            eventLatch.countDown();
        });

        // Connect to FreeSWITCH
        L.info("Connecting to FreeSWITCH at {}:{}...", HOST, PORT);
        client.connect(new InetSocketAddress(HOST, PORT), PASSWORD, TIMEOUT_SECONDS);
        L.info("Connected and authenticated!");

        // Subscribe to all events
        client.setEventSubscriptions(EventFormat.PLAIN, "all");
        L.info("Subscribed to all events");

        // Wait for events
        L.info("Listening for events... (waiting up to {} seconds)", TIMEOUT_SECONDS);
        boolean receivedEvent = eventLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Verify we received events
        assertTrue(receivedEvent, "Should have received at least one event");
        assertNotNull(eventName.get(), "Event name should not be null");
        L.info("Test completed successfully!");
    }

    @Test
    void testClientInstantiation() {
        // Unit test that doesn't require FreeSWITCH
        assertNotNull(client, "Client should be instantiated");
        L.info("Client instantiation test passed");
    }

    @Test
    void testEventFormatEnum() {
        // Unit test for EventFormat enum
        assertEquals("plain", EventFormat.PLAIN.toString());
        assertEquals("xml", EventFormat.XML.toString());
        assertEquals("json", EventFormat.JSON.toString());
        L.info("EventFormat enum test passed");
    }
}
