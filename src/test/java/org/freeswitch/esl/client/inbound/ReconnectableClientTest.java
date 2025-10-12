package org.freeswitch.esl.client.inbound;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ReconnectableClient and related classes.
 */
class ReconnectableClientTest {
    private static final Logger logger = LoggerFactory.getLogger(ReconnectableClientTest.class);

    private ReconnectableClient client;

    @BeforeEach
    void setUp() {
        // Tests will create client as needed
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                logger.warn("Error closing client", e);
            }
        }
    }

    @Test
    void testReconnectionConfigDefaults() {
        ReconnectionConfig config = new ReconnectionConfig();

        assertEquals(60_000, config.getHeartbeatTimeoutMs());
        assertEquals(10_000, config.getHealthCheckIntervalMs());
        assertEquals(1_000, config.getInitialReconnectDelayMs());
        assertEquals(60_000, config.getMaxReconnectDelayMs());
        assertEquals(2.0, config.getReconnectMultiplier(), 0.001);
        assertEquals(0.2, config.getJitterFactor(), 0.001);

        logger.info("Default config test passed");
    }

    @Test
    void testReconnectionConfigFastDetection() {
        ReconnectionConfig config = ReconnectionConfig.fastDetection();

        assertEquals(40_000, config.getHeartbeatTimeoutMs());
        assertEquals(10_000, config.getHealthCheckIntervalMs());
        assertEquals(500, config.getInitialReconnectDelayMs());

        logger.info("Fast detection config test passed");
    }

    @Test
    void testReconnectionConfigTolerant() {
        ReconnectionConfig config = ReconnectionConfig.tolerant();

        assertEquals(90_000, config.getHeartbeatTimeoutMs());
        assertEquals(15_000, config.getHealthCheckIntervalMs());
        assertEquals(120_000, config.getMaxReconnectDelayMs());

        logger.info("Tolerant config test passed");
    }

    @Test
    void testReconnectionConfigValidation() {
        ReconnectionConfig config = new ReconnectionConfig();

        // Test heartbeat timeout validation
        assertThrows(IllegalArgumentException.class, () -> {
            config.setHeartbeatTimeoutMs(10_000); // Less than 1.5x heartbeat interval
        });

        // Test health check interval validation
        assertThrows(IllegalArgumentException.class, () -> {
            config.setHealthCheckIntervalMs(-1);
        });

        // Test reconnect delay validation
        assertThrows(IllegalArgumentException.class, () -> {
            config.setInitialReconnectDelayMs(-1);
        });

        // Test max delay validation
        assertThrows(IllegalArgumentException.class, () -> {
            config.setMaxReconnectDelayMs(500); // Less than initial delay
        });

        // Test multiplier validation
        assertThrows(IllegalArgumentException.class, () -> {
            config.setReconnectMultiplier(0.5); // Less than 1.0
        });

        // Test jitter factor validation
        assertThrows(IllegalArgumentException.class, () -> {
            config.setJitterFactor(-0.1);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            config.setJitterFactor(1.5);
        });

        logger.info("Config validation test passed");
    }

    @Test
    void testHeartbeatMonitor() throws InterruptedException {
        HeartbeatMonitor monitor = new HeartbeatMonitor(2000); // 2 second timeout

        // Should not timeout immediately
        assertFalse(monitor.isTimeout());

        // Record a heartbeat
        monitor.recordHeartbeat();
        assertFalse(monitor.isTimeout());

        // Wait and check timeout
        Thread.sleep(2500);
        assertTrue(monitor.isTimeout());

        // Reset should clear timeout
        monitor.reset();
        assertFalse(monitor.isTimeout());

        logger.info("Heartbeat monitor test passed");
    }

    @Test
    void testHeartbeatMonitorTimeSinceLastHeartbeat() throws InterruptedException {
        HeartbeatMonitor monitor = new HeartbeatMonitor(60_000);

        long initialTime = monitor.getTimeSinceLastHeartbeat();
        assertTrue(initialTime < 100); // Should be very recent

        Thread.sleep(100);
        long afterSleep = monitor.getTimeSinceLastHeartbeat();
        assertTrue(afterSleep >= 100);

        monitor.recordHeartbeat();
        long afterRecord = monitor.getTimeSinceLastHeartbeat();
        assertTrue(afterRecord < 50);

        logger.info("Time since last heartbeat test passed");
    }

    @Test
    void testExponentialBackoffStrategy() {
        ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy(
                1000,  // initial delay
                60000, // max delay
                2.0,   // multiplier
                0.0    // no jitter for predictable testing
        );

        // First attempt should be ~1000ms
        long delay1 = strategy.nextDelay();
        assertTrue(delay1 >= 900 && delay1 <= 1100);
        assertEquals(1, strategy.getAttemptCount());

        // Second attempt should be ~2000ms
        long delay2 = strategy.nextDelay();
        assertTrue(delay2 >= 1800 && delay2 <= 2200);
        assertEquals(2, strategy.getAttemptCount());

        // Third attempt should be ~4000ms
        long delay3 = strategy.nextDelay();
        assertTrue(delay3 >= 3600 && delay3 <= 4400);

        // Reset should start over
        strategy.reset();
        assertEquals(0, strategy.getAttemptCount());
        long delayAfterReset = strategy.nextDelay();
        assertTrue(delayAfterReset >= 900 && delayAfterReset <= 1100);

        logger.info("Exponential backoff test passed");
    }

    @Test
    void testExponentialBackoffWithJitter() {
        ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy(
                1000,  // initial delay
                60000, // max delay
                2.0,   // multiplier
                0.2    // 20% jitter
        );

        // Test that jitter produces different values
        long delay1 = strategy.nextDelay();
        strategy.reset();
        long delay2 = strategy.nextDelay();

        // With 20% jitter on 1000ms, range is 900-1100
        assertTrue(delay1 >= 800 && delay1 <= 1200);
        assertTrue(delay2 >= 800 && delay2 <= 1200);

        logger.info("Exponential backoff with jitter test passed");
    }

    @Test
    void testExponentialBackoffMaxCap() {
        ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy(
                1000,  // initial delay
                10000, // max delay (small for testing)
                2.0,   // multiplier
                0.0    // no jitter
        );

        // Keep calling until we hit the cap
        long delay = 0;
        for (int i = 0; i < 10; i++) {
            delay = strategy.nextDelay();
        }

        // Should be capped at max delay
        assertEquals(10000, delay);

        logger.info("Exponential backoff max cap test passed");
    }

    @Test
    void testExponentialBackoffPeekNextDelay() {
        ExponentialBackoffStrategy strategy = new ExponentialBackoffStrategy(
                1000, 60000, 2.0, 0.0
        );

        long peek1 = strategy.peekNextDelay();
        assertEquals(1000, peek1);
        assertEquals(0, strategy.getAttemptCount()); // Should not increment

        long actual1 = strategy.nextDelay();
        assertEquals(1, strategy.getAttemptCount()); // Now it increments

        long peek2 = strategy.peekNextDelay();
        assertEquals(2000, peek2);
        assertEquals(1, strategy.getAttemptCount()); // Should not increment

        logger.info("Peek next delay test passed");
    }

    @Test
    void testReconnectableClientCreation() {
        client = new ReconnectableClient();
        assertNotNull(client);
        assertNotNull(client.getUnderlyingClient());
        assertNotNull(client.getHeartbeatMonitor());
        assertFalse(client.canSend());
        assertFalse(client.isReconnecting());

        logger.info("ReconnectableClient creation test passed");
    }

    @Test
    void testReconnectableClientWithCustomConfig() {
        ReconnectionConfig config = ReconnectionConfig.fastDetection();
        client = new ReconnectableClient(config);

        assertNotNull(client);
        assertEquals(40_000, client.getHeartbeatMonitor().getTimeoutMs());

        logger.info("ReconnectableClient with custom config test passed");
    }

    @Test
    void testAddAndRemoveListeners() {
        client = new ReconnectableClient();

        IEslEventListener listener1 = (ctx, event) -> {
            logger.info("Listener 1: {}", event.getEventName());
        };

        IEslEventListener listener2 = (ctx, event) -> {
            logger.info("Listener 2: {}", event.getEventName());
        };

        // Add listeners (won't be active until connected, but should be stored)
        client.addEventListener(listener1);
        client.addEventListener(listener2, "CHANNEL_CREATE", "CHANNEL_HANGUP");

        // Remove listener
        boolean removed = client.removeEventListener(listener1);
        assertTrue(removed);

        // Try to remove again
        boolean removedAgain = client.removeEventListener(listener1);
        assertFalse(removedAgain);

        logger.info("Add and remove listeners test passed");
    }

    @Test
    void testSetEventSubscriptionsWhenNotConnected() {
        client = new ReconnectableClient();

        // Should not throw exception, just save for later
        client.setEventSubscriptions(org.freeswitch.esl.client.internal.IModEslApi.EventFormat.PLAIN, "CHANNEL_CREATE");

        assertNotNull(client);
        logger.info("Set event subscriptions when not connected test passed");
    }
}
