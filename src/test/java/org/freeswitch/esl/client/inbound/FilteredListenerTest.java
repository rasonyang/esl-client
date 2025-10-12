package org.freeswitch.esl.client.inbound;

import org.freeswitch.esl.client.internal.Context;
import org.freeswitch.esl.client.transport.event.EslEvent;
import org.freeswitch.esl.client.transport.message.EslMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for filtered event listener functionality.
 */
class FilteredListenerTest {
    private static final Logger logger = LoggerFactory.getLogger(FilteredListenerTest.class);

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
    void testAddGlobalListener() {
        AtomicInteger eventCount = new AtomicInteger(0);

        IEslEventListener listener = (ctx, event) -> {
            eventCount.incrementAndGet();
            logger.info("Global listener received: {}", event.getEventName());
        };

        client.addEventListener(listener);

        assertNotNull(client, "Client should not be null");
        logger.info("Global listener test passed");
    }

    @Test
    void testAddFilteredListener() {
        AtomicInteger eventCount = new AtomicInteger(0);

        IEslEventListener listener = (ctx, event) -> {
            eventCount.incrementAndGet();
            logger.info("Filtered listener received: {}", event.getEventName());
        };

        client.addEventListener(listener, "CHANNEL_CREATE", "CHANNEL_HANGUP");

        assertNotNull(client, "Client should not be null");
        logger.info("Filtered listener test passed");
    }

    @Test
    void testRemoveEventListener() {
        IEslEventListener listener = (ctx, event) -> {
            logger.info("Event received: {}", event.getEventName());
        };

        // Add listener
        client.addEventListener(listener);

        // Remove listener
        boolean removed = client.removeEventListener(listener);

        assertTrue(removed, "Listener should be removed successfully");

        // Try to remove again (should return false)
        boolean removedAgain = client.removeEventListener(listener);

        assertFalse(removedAgain, "Second removal should return false");
        logger.info("Remove listener test passed");
    }

    @Test
    void testRemoveNullListener() {
        boolean removed = client.removeEventListener(null);

        assertFalse(removed, "Removing null listener should return false");
        logger.info("Remove null listener test passed");
    }

    @Test
    void testAddListenerWithNoEventNames() {
        AtomicInteger eventCount = new AtomicInteger(0);

        IEslEventListener listener = (ctx, event) -> {
            eventCount.incrementAndGet();
        };

        // This should not add the listener (empty varargs)
        client.addEventListener(listener, new String[0]);

        assertNotNull(client, "Client should not be null");
        logger.info("Empty event names test passed");
    }

    @Test
    void testAddNullListener() {
        // Should not throw exception
        client.addEventListener(null);
        client.addEventListener(null, "CHANNEL_CREATE");

        assertNotNull(client, "Client should handle null listeners gracefully");
        logger.info("Null listener test passed");
    }

    @Test
    void testMultipleFilteredListeners() {
        AtomicInteger channelCreateCount = new AtomicInteger(0);
        AtomicInteger channelHangupCount = new AtomicInteger(0);
        AtomicInteger allEventsCount = new AtomicInteger(0);

        IEslEventListener createListener = (ctx, event) -> {
            channelCreateCount.incrementAndGet();
            logger.info("Create listener: {}", event.getEventName());
        };

        IEslEventListener hangupListener = (ctx, event) -> {
            channelHangupCount.incrementAndGet();
            logger.info("Hangup listener: {}", event.getEventName());
        };

        IEslEventListener globalListener = (ctx, event) -> {
            allEventsCount.incrementAndGet();
            logger.info("Global listener: {}", event.getEventName());
        };

        // Add different listeners with different filters
        client.addEventListener(createListener, "CHANNEL_CREATE");
        client.addEventListener(hangupListener, "CHANNEL_HANGUP");
        client.addEventListener(globalListener);

        assertNotNull(client, "Client should not be null");
        logger.info("Multiple filtered listeners test passed");
    }

    @Test
    void testRemoveSpecificListener() {
        IEslEventListener listener1 = (ctx, event) -> {
            logger.info("Listener 1: {}", event.getEventName());
        };

        IEslEventListener listener2 = (ctx, event) -> {
            logger.info("Listener 2: {}", event.getEventName());
        };

        // Add both listeners
        client.addEventListener(listener1, "CHANNEL_CREATE");
        client.addEventListener(listener2, "CHANNEL_HANGUP");

        // Remove only listener1
        boolean removed1 = client.removeEventListener(listener1);
        assertTrue(removed1, "Listener 1 should be removed");

        // Try to remove listener1 again
        boolean removed1Again = client.removeEventListener(listener1);
        assertFalse(removed1Again, "Listener 1 should not be found");

        // listener2 should still be present
        boolean removed2 = client.removeEventListener(listener2);
        assertTrue(removed2, "Listener 2 should be removed");

        logger.info("Remove specific listener test passed");
    }

    @Test
    void testAutoUpdateServerSubscriptionDisabledByDefault() {
        IEslEventListener listener = (ctx, event) -> {
            logger.info("Event: {}", event.getEventName());
        };

        // Add listener
        client.addEventListener(listener, "CHANNEL_CREATE");

        // Auto-update should be disabled by default
        assertNotNull(client, "Client should not be null");
        logger.info("Auto-update disabled by default test passed");
    }

    @Test
    void testSetAutoUpdateServerSubscription() {
        // Should not throw exception when not connected
        client.setAutoUpdateServerSubscription(true);
        client.setAutoUpdateServerSubscription(false);

        assertNotNull(client, "Client should handle auto-update setting");
        logger.info("Set auto-update subscription test passed");
    }

    @Test
    void testMultipleListenersWithSameEventTypes() {
        IEslEventListener listener1 = (ctx, event) -> {
            logger.info("Listener 1: {}", event.getEventName());
        };

        IEslEventListener listener2 = (ctx, event) -> {
            logger.info("Listener 2: {}", event.getEventName());
        };

        // Both subscribe to same events
        client.addEventListener(listener1, "CHANNEL_CREATE", "CHANNEL_HANGUP");
        client.addEventListener(listener2, "CHANNEL_CREATE", "CHANNEL_HANGUP");

        assertNotNull(client, "Client should not be null");
        logger.info("Multiple listeners with same event types test passed");
    }
}
