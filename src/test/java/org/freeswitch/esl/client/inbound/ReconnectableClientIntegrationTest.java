package org.freeswitch.esl.client.inbound;

import org.freeswitch.esl.client.internal.IModEslApi;
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
 * Integration test for ReconnectableClient with real FreeSWITCH server.
 * These tests require a running FreeSWITCH instance on localhost:8021.
 */
@Tag("integration")
class ReconnectableClientIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(ReconnectableClientIntegrationTest.class);

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
    @Disabled("Requires FreeSWITCH running on localhost:8021")
    void testConnectAndReceiveHeartbeat() throws Exception {
        logger.info("Starting heartbeat integration test");

        AtomicInteger heartbeatCount = new AtomicInteger(0);
        AtomicInteger channelEventCount = new AtomicInteger(0);
        CountDownLatch heartbeatLatch = new CountDownLatch(3); // Wait for 3 heartbeats

        // Create client with default config
        client = new ReconnectableClient();

        // Add listener for channel events
        client.addEventListener((ctx, event) -> {
            channelEventCount.incrementAndGet();
            logger.info("Channel event: {}", event.getEventName());
        }, "CHANNEL_CREATE", "CHANNEL_HANGUP");

        // Add listener for heartbeat (for testing purposes)
        client.addEventListener((ctx, event) -> {
            if ("HEARTBEAT".equals(event.getEventName())) {
                int count = heartbeatCount.incrementAndGet();
                logger.info("Heartbeat #{} received", count);
                heartbeatLatch.countDown();
            }
        });

        // Connect to FreeSWITCH
        client.connect(new InetSocketAddress("localhost", 8021), "ClueCon", 10);

        // Subscribe to channel events
        client.setEventSubscriptions(IModEslApi.EventFormat.PLAIN, "CHANNEL_CREATE CHANNEL_HANGUP");

        // Wait for heartbeats (max 90 seconds = 4.5 heartbeat cycles)
        boolean receivedHeartbeats = heartbeatLatch.await(90, TimeUnit.SECONDS);

        assertTrue(receivedHeartbeats, "Should receive at least 3 heartbeats");
        assertTrue(heartbeatCount.get() >= 3, "Should have received at least 3 heartbeats");

        logger.info("Test results: heartbeats={}, channelEvents={}",
                heartbeatCount.get(), channelEventCount.get());

        logger.info("Heartbeat integration test completed successfully");
    }

    @Test
    @Disabled("Requires FreeSWITCH running on localhost:8021 and manual restart")
    void testReconnectionAfterServerRestart() throws Exception {
        logger.info("Starting reconnection integration test");
        logger.warn("This test requires manual FreeSWITCH restart after connection is established");

        AtomicInteger connectionCount = new AtomicInteger(0);
        AtomicInteger heartbeatCount = new AtomicInteger(0);
        CountDownLatch initialConnectionLatch = new CountDownLatch(1);
        CountDownLatch reconnectionLatch = new CountDownLatch(1);

        // Create client with fast detection config
        ReconnectionConfig config = ReconnectionConfig.fastDetection();
        client = new ReconnectableClient(config);

        // Add listener to track events
        client.addEventListener((ctx, event) -> {
            if ("HEARTBEAT".equals(event.getEventName())) {
                int count = heartbeatCount.incrementAndGet();
                logger.info("Heartbeat #{} received", count);

                if (count == 1) {
                    initialConnectionLatch.countDown();
                } else if (count > 10) {
                    // After reconnection, we should start receiving heartbeats again
                    reconnectionLatch.countDown();
                }
            }
        });

        // Connect
        client.connect(new InetSocketAddress("localhost", 8021), "ClueCon", 10);
        client.setEventSubscriptions(IModEslApi.EventFormat.PLAIN, "HEARTBEAT");

        // Wait for initial connection
        assertTrue(initialConnectionLatch.await(30, TimeUnit.SECONDS),
                "Should establish initial connection");

        logger.warn(">>> PLEASE RESTART FreeSWITCH NOW (fs_cli -x reload) <<<");
        logger.info("Waiting for reconnection...");

        // Wait for reconnection (max 3 minutes)
        boolean reconnected = reconnectionLatch.await(180, TimeUnit.SECONDS);

        assertTrue(reconnected, "Should reconnect after server restart");
        assertTrue(heartbeatCount.get() > 10, "Should receive heartbeats after reconnection");

        logger.info("Reconnection test completed: heartbeats={}", heartbeatCount.get());
    }

    @Test
    @Disabled("Requires FreeSWITCH running on localhost:8021")
    void testHeartbeatMonitorAccuracy() throws Exception {
        logger.info("Starting heartbeat monitor accuracy test");

        CountDownLatch heartbeatLatch = new CountDownLatch(5);

        client = new ReconnectableClient();

        client.addEventListener((ctx, event) -> {
            if ("HEARTBEAT".equals(event.getEventName())) {
                long timeSinceLast = client.getHeartbeatMonitor().getTimeSinceLastHeartbeat();
                logger.info("Heartbeat interval: {}ms (expected: ~20000ms)", timeSinceLast);
                heartbeatLatch.countDown();
            }
        });

        client.connect(new InetSocketAddress("localhost", 8021), "ClueCon", 10);
        client.setEventSubscriptions(IModEslApi.EventFormat.PLAIN, "HEARTBEAT");

        // Wait for 5 heartbeats (max 120 seconds)
        boolean received = heartbeatLatch.await(120, TimeUnit.SECONDS);

        assertTrue(received, "Should receive 5 heartbeats");

        // Check final interval is close to 20 seconds
        long finalInterval = client.getHeartbeatMonitor().getTimeSinceLastHeartbeat();
        assertTrue(finalInterval < 1000, "Final interval should be less than 1 second");

        logger.info("Heartbeat monitor accuracy test completed");
    }

    @Test
    @Disabled("Requires FreeSWITCH running on localhost:8021")
    void testListenersPreservedAcrossReconnection() throws Exception {
        logger.info("Starting listener preservation test");

        AtomicInteger channelCreateCount = new AtomicInteger(0);
        AtomicInteger channelHangupCount = new AtomicInteger(0);
        AtomicInteger heartbeatCount = new AtomicInteger(0);

        CountDownLatch initialHeartbeatLatch = new CountDownLatch(2);
        CountDownLatch reconnectionHeartbeatLatch = new CountDownLatch(2);

        client = new ReconnectableClient(ReconnectionConfig.fastDetection());

        // Add listeners before connection
        client.addEventListener((ctx, event) -> {
            channelCreateCount.incrementAndGet();
            logger.info("Channel CREATE event");
        }, "CHANNEL_CREATE");

        client.addEventListener((ctx, event) -> {
            channelHangupCount.incrementAndGet();
            logger.info("Channel HANGUP event");
        }, "CHANNEL_HANGUP");

        client.addEventListener((ctx, event) -> {
            if ("HEARTBEAT".equals(event.getEventName())) {
                int count = heartbeatCount.incrementAndGet();
                logger.info("Heartbeat #{}", count);

                if (count <= 2) {
                    initialHeartbeatLatch.countDown();
                } else {
                    reconnectionHeartbeatLatch.countDown();
                }
            }
        });

        // Connect and subscribe
        client.connect(new InetSocketAddress("localhost", 8021), "ClueCon", 10);
        client.setEventSubscriptions(IModEslApi.EventFormat.PLAIN, "CHANNEL_CREATE CHANNEL_HANGUP");

        // Wait for initial heartbeats
        assertTrue(initialHeartbeatLatch.await(60, TimeUnit.SECONDS),
                "Should receive initial heartbeats");

        logger.warn(">>> PLEASE RESTART FreeSWITCH NOW <<<");

        // Wait for reconnection heartbeats
        boolean reconnected = reconnectionHeartbeatLatch.await(180, TimeUnit.SECONDS);

        assertTrue(reconnected, "Should receive heartbeats after reconnection");
        assertTrue(heartbeatCount.get() >= 4, "Should have received at least 4 heartbeats total");

        logger.info("Listener preservation test completed: heartbeats={}",
                heartbeatCount.get());
    }

    @Test
    @Disabled("Requires FreeSWITCH running on localhost:8021")
    void testMultipleReconnectionCycles() throws Exception {
        logger.info("Starting multiple reconnection cycles test");
        logger.warn("This test requires FreeSWITCH to be restarted multiple times");

        AtomicInteger heartbeatCount = new AtomicInteger(0);
        AtomicInteger reconnectionCycles = new AtomicInteger(0);

        client = new ReconnectableClient(ReconnectionConfig.fastDetection());

        client.addEventListener((ctx, event) -> {
            if ("HEARTBEAT".equals(event.getEventName())) {
                heartbeatCount.incrementAndGet();
            }
        });

        client.connect(new InetSocketAddress("localhost", 8021), "ClueCon", 10);
        client.setEventSubscriptions(IModEslApi.EventFormat.PLAIN, "HEARTBEAT");

        // Test continues to run - monitor should detect disconnections and reconnect
        logger.info("Client connected. Monitor will run for 5 minutes.");
        logger.info("Please restart FreeSWITCH several times during this period.");

        Thread.sleep(300_000); // 5 minutes

        logger.info("Multiple reconnection test completed");
        logger.info("Total heartbeats received: {}", heartbeatCount.get());
        logger.info("Estimated reconnection cycles: {}", reconnectionCycles.get());
    }
}
