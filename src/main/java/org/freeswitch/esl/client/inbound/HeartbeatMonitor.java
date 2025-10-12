package org.freeswitch.esl.client.inbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Monitors FreeSWITCH HEARTBEAT events to detect connection health.
 * FreeSWITCH sends HEARTBEAT events every 20 seconds by default.
 * <p>
 * This monitor tracks the last received heartbeat timestamp and provides
 * timeout detection based on a configurable threshold.
 */
public class HeartbeatMonitor {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatMonitor.class);

    private volatile long lastHeartbeatAt;
    private final long timeoutMs;

    /**
     * Creates a new heartbeat monitor.
     *
     * @param timeoutMs heartbeat timeout threshold in milliseconds
     */
    public HeartbeatMonitor(long timeoutMs) {
        // Validate timeout is at least 1.5x heartbeat interval
        if (timeoutMs < ReconnectionConfig.FREESWITCH_HEARTBEAT_INTERVAL_MS * 1.5) {
            log.warn("Heartbeat timeout {}ms is less than 1.5x heartbeat interval (30s), " +
                    "may cause false positives", timeoutMs);
        }

        this.timeoutMs = timeoutMs;
        this.lastHeartbeatAt = System.currentTimeMillis();

        log.info("HeartbeatMonitor initialized with timeout: {}ms", timeoutMs);
    }

    /**
     * Checks if heartbeat has timed out.
     *
     * @return true if elapsed time since last heartbeat exceeds timeout threshold
     */
    public boolean isTimeout() {
        long elapsed = System.currentTimeMillis() - lastHeartbeatAt;
        boolean timeout = elapsed > timeoutMs;

        if (timeout) {
            log.warn("Heartbeat timeout detected: {}ms elapsed (threshold: {}ms)",
                    elapsed, timeoutMs);
        }

        return timeout;
    }

    /**
     * Records a heartbeat event.
     * Should be called when a HEARTBEAT event is received from FreeSWITCH.
     */
    public void recordHeartbeat() {
        long now = System.currentTimeMillis();
        long interval = now - lastHeartbeatAt;

        log.debug("Heartbeat received, interval: {}ms", interval);

        // Warn if interval significantly exceeds expected
        if (interval > ReconnectionConfig.FREESWITCH_HEARTBEAT_INTERVAL_MS * 2) {
            log.warn("Heartbeat interval {}ms exceeds expected {}ms (2x normal)",
                    interval, ReconnectionConfig.FREESWITCH_HEARTBEAT_INTERVAL_MS);
        }

        lastHeartbeatAt = now;
    }

    /**
     * Gets the time elapsed since the last heartbeat.
     *
     * @return milliseconds since last heartbeat
     */
    public long getTimeSinceLastHeartbeat() {
        return System.currentTimeMillis() - lastHeartbeatAt;
    }

    /**
     * Gets the timestamp of the last heartbeat.
     *
     * @return timestamp in milliseconds
     */
    public long getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    /**
     * Gets the configured timeout threshold.
     *
     * @return timeout in milliseconds
     */
    public long getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * Resets the heartbeat timestamp to current time.
     * Useful when reconnecting.
     */
    public void reset() {
        lastHeartbeatAt = System.currentTimeMillis();
        log.debug("Heartbeat monitor reset");
    }

    @Override
    public String toString() {
        return "HeartbeatMonitor{" +
                "timeoutMs=" + timeoutMs +
                ", timeSinceLastHeartbeat=" + getTimeSinceLastHeartbeat() + "ms" +
                '}';
    }
}
