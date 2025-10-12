package org.freeswitch.esl.client.inbound;

import org.freeswitch.esl.client.internal.IModEslApi.EventFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for filtered event listeners with real FreeSWITCH server.
 */
@Tag("integration")
class FilteredListenerIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(FilteredListenerIntegrationTest.class);

    private Client client;

    @BeforeEach
    void setUp() {
        client = new Client();
    }

    @AfterEach
    void tearDown() {
        if (client != null && client.canSend()) {
            try {
                client.close();
            } catch (Exception e) {
                logger.warn("Error closing client", e);
            }
        }
    }

    @Test
    @Disabled("Requires FreeSWITCH running on localhost:8021")
    void testFilteredListenersWithRealFreeSWITCH() throws Exception {
        logger.info("Starting filtered listener integration test");

        // Counters for different event types
        AtomicInteger channelCreateCount = new AtomicInteger(0);
        AtomicInteger channelHangupCount = new AtomicInteger(0);
        AtomicInteger heartbeatCount = new AtomicInteger(0);
        AtomicInteger allEventsCount = new AtomicInteger(0);

        CountDownLatch latch = new CountDownLatch(10); // Wait for at least 10 events

        // Listener that only receives CHANNEL_CREATE events
        IEslEventListener createListener = (ctx, event) -> {
            channelCreateCount.incrementAndGet();
            logger.info("CREATE LISTENER - Event: {}, UUID: {}",
                    event.getEventName(),
                    event.getEventHeaders().get("Unique-ID"));
            latch.countDown();
        };

        // Listener that only receives CHANNEL_HANGUP events
        IEslEventListener hangupListener = (ctx, event) -> {
            channelHangupCount.incrementAndGet();
            logger.info("HANGUP LISTENER - Event: {}, UUID: {}",
                    event.getEventName(),
                    event.getEventHeaders().get("Unique-ID"));
            latch.countDown();
        };

        // Listener that only receives HEARTBEAT events
        IEslEventListener heartbeatListener = (ctx, event) -> {
            heartbeatCount.incrementAndGet();
            logger.info("HEARTBEAT LISTENER - Event: {}", event.getEventName());
            latch.countDown();
        };

        // Global listener that receives all events
        IEslEventListener globalListener = (ctx, event) -> {
            allEventsCount.incrementAndGet();
            logger.info("GLOBAL LISTENER - Event: {}", event.getEventName());
        };

        // Add filtered listeners
        client.addEventListener(createListener, "CHANNEL_CREATE");
        client.addEventListener(hangupListener, "CHANNEL_HANGUP", "CHANNEL_DESTROY");
        client.addEventListener(heartbeatListener, "HEARTBEAT");
        client.addEventListener(globalListener);

        // Connect to FreeSWITCH
        client.connect(new InetSocketAddress("localhost", 8021), "ClueCon", 10);

        // Subscribe to all events
        client.setEventSubscriptions(EventFormat.PLAIN, "all");

        logger.info("Connected and subscribed to events. Waiting for events...");

        // Wait for events (30 seconds timeout)
        boolean receivedEvents = latch.await(30, TimeUnit.SECONDS);

        logger.info("Test results:");
        logger.info("  CHANNEL_CREATE events: {}", channelCreateCount.get());
        logger.info("  CHANNEL_HANGUP events: {}", channelHangupCount.get());
        logger.info("  HEARTBEAT events: {}", heartbeatCount.get());
        logger.info("  All events (global): {}", allEventsCount.get());

        // Verify that global listener received more events than filtered listeners
        assertTrue(allEventsCount.get() >= channelCreateCount.get(),
                "Global listener should receive at least as many events as create listener");
        assertTrue(allEventsCount.get() >= heartbeatCount.get(),
                "Global listener should receive at least as many events as heartbeat listener");

        // Test removing a listener
        boolean removed = client.removeEventListener(heartbeatListener);
        assertTrue(removed, "Should successfully remove heartbeat listener");

        logger.info("Filtered listener integration test completed");
    }

    @Test
    @Disabled("Requires FreeSWITCH running on localhost:8021")
    void testRemoveListenerDuringExecution() throws Exception {
        logger.info("Starting remove listener during execution test");

        AtomicInteger eventCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(5);

        IEslEventListener listener = (ctx, event) -> {
            int count = eventCount.incrementAndGet();
            logger.info("Event #{}: {}", count, event.getEventName());
            latch.countDown();
        };

        // Add listener for HEARTBEAT events
        client.addEventListener(listener, "HEARTBEAT");

        // Connect and subscribe
        client.connect(new InetSocketAddress("localhost", 8021), "ClueCon", 10);
        client.setEventSubscriptions(EventFormat.PLAIN, "HEARTBEAT");

        logger.info("Waiting for 5 HEARTBEAT events...");
        latch.await(30, TimeUnit.SECONDS);

        int eventsBeforeRemoval = eventCount.get();
        logger.info("Received {} events, now removing listener", eventsBeforeRemoval);

        // Remove the listener
        boolean removed = client.removeEventListener(listener);
        assertTrue(removed, "Should successfully remove listener");

        // Wait a bit more
        Thread.sleep(10000);

        int eventsAfterRemoval = eventCount.get();
        logger.info("Events after removal: {}", eventsAfterRemoval);

        // Should not have received many more events after removal
        assertTrue(eventsAfterRemoval - eventsBeforeRemoval < 3,
                "Should not receive many events after removing listener");

        logger.info("Remove listener test completed");
    }
}
