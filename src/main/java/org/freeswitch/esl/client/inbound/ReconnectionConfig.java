package org.freeswitch.esl.client.inbound;

/**
 * Configuration for automatic reconnection behavior.
 * Defines parameters for heartbeat monitoring, health checks, and reconnection strategy.
 */
public class ReconnectionConfig {

    /**
     * FreeSWITCH default heartbeat interval (20 seconds).
     */
    public static final long FREESWITCH_HEARTBEAT_INTERVAL_MS = 20_000;

    /**
     * Heartbeat timeout threshold in milliseconds.
     * FreeSWITCH sends HEARTBEAT events every 20 seconds by default.
     * <p>
     * Recommended values:
     * <ul>
     * <li>40000ms (2x): Fast detection, stable network</li>
     * <li>60000ms (3x): Default, tolerates brief network jitter (RECOMMENDED)</li>
     * <li>90000ms (4.5x): More tolerant, unstable network</li>
     * </ul>
     * <p>
     * Default: 60 seconds = 3 heartbeat cycles, allows missing 2 heartbeats
     */
    private long heartbeatTimeoutMs = 60_000;

    /**
     * Health check interval in milliseconds.
     * The reconnectable client checks for heartbeat timeout at this interval.
     * Should be less than heartbeat interval for timely detection.
     * <p>
     * Recommended: 10000ms (0.5x heartbeat interval)
     * <p>
     * Default: 10 seconds
     */
    private long healthCheckIntervalMs = 10_000;

    /**
     * Initial reconnection delay in milliseconds.
     * First retry happens after this delay.
     * <p>
     * Default: 1 second
     */
    private long initialReconnectDelayMs = 1_000;

    /**
     * Maximum reconnection delay in milliseconds.
     * Delay will not exceed this value even with exponential backoff.
     * <p>
     * Default: 60 seconds
     */
    private long maxReconnectDelayMs = 60_000;

    /**
     * Exponential backoff multiplier.
     * Next delay = previous delay * multiplier (capped by maxReconnectDelayMs)
     * <p>
     * Default: 2.0
     */
    private double reconnectMultiplier = 2.0;

    /**
     * Jitter factor for randomization (0.0 - 1.0).
     * Adds ±(jitterFactor * delay / 2) randomness to avoid thundering herd.
     * <p>
     * Default: 0.2 (±10% randomness)
     */
    private double jitterFactor = 0.2;

    public ReconnectionConfig() {
    }

    /**
     * Creates a fast detection configuration for stable networks.
     * Heartbeat timeout: 40s (2 cycles)
     * Health check interval: 10s
     */
    public static ReconnectionConfig fastDetection() {
        ReconnectionConfig config = new ReconnectionConfig();
        config.heartbeatTimeoutMs = 40_000;
        config.healthCheckIntervalMs = 10_000;
        config.initialReconnectDelayMs = 500;
        return config;
    }

    /**
     * Creates a tolerant configuration for unstable networks.
     * Heartbeat timeout: 90s (4.5 cycles)
     * Health check interval: 15s
     * Max reconnect delay: 120s
     */
    public static ReconnectionConfig tolerant() {
        ReconnectionConfig config = new ReconnectionConfig();
        config.heartbeatTimeoutMs = 90_000;
        config.healthCheckIntervalMs = 15_000;
        config.maxReconnectDelayMs = 120_000;
        return config;
    }

    // Getters and setters

    public long getHeartbeatTimeoutMs() {
        return heartbeatTimeoutMs;
    }

    public void setHeartbeatTimeoutMs(long heartbeatTimeoutMs) {
        if (heartbeatTimeoutMs < FREESWITCH_HEARTBEAT_INTERVAL_MS * 1.5) {
            throw new IllegalArgumentException(
                    "Heartbeat timeout must be at least 1.5x heartbeat interval (30s), got: " + heartbeatTimeoutMs + "ms");
        }
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    }

    public long getHealthCheckIntervalMs() {
        return healthCheckIntervalMs;
    }

    public void setHealthCheckIntervalMs(long healthCheckIntervalMs) {
        if (healthCheckIntervalMs <= 0) {
            throw new IllegalArgumentException("Health check interval must be positive, got: " + healthCheckIntervalMs);
        }
        this.healthCheckIntervalMs = healthCheckIntervalMs;
    }

    public long getInitialReconnectDelayMs() {
        return initialReconnectDelayMs;
    }

    public void setInitialReconnectDelayMs(long initialReconnectDelayMs) {
        if (initialReconnectDelayMs < 0) {
            throw new IllegalArgumentException("Initial reconnect delay cannot be negative, got: " + initialReconnectDelayMs);
        }
        this.initialReconnectDelayMs = initialReconnectDelayMs;
    }

    public long getMaxReconnectDelayMs() {
        return maxReconnectDelayMs;
    }

    public void setMaxReconnectDelayMs(long maxReconnectDelayMs) {
        if (maxReconnectDelayMs < initialReconnectDelayMs) {
            throw new IllegalArgumentException(
                    "Max reconnect delay must be >= initial delay, got: " + maxReconnectDelayMs + " < " + initialReconnectDelayMs);
        }
        this.maxReconnectDelayMs = maxReconnectDelayMs;
    }

    public double getReconnectMultiplier() {
        return reconnectMultiplier;
    }

    public void setReconnectMultiplier(double reconnectMultiplier) {
        if (reconnectMultiplier < 1.0) {
            throw new IllegalArgumentException("Reconnect multiplier must be >= 1.0, got: " + reconnectMultiplier);
        }
        this.reconnectMultiplier = reconnectMultiplier;
    }

    public double getJitterFactor() {
        return jitterFactor;
    }

    public void setJitterFactor(double jitterFactor) {
        if (jitterFactor < 0.0 || jitterFactor > 1.0) {
            throw new IllegalArgumentException("Jitter factor must be between 0.0 and 1.0, got: " + jitterFactor);
        }
        this.jitterFactor = jitterFactor;
    }

    @Override
    public String toString() {
        return "ReconnectionConfig{" +
                "heartbeatTimeoutMs=" + heartbeatTimeoutMs +
                ", healthCheckIntervalMs=" + healthCheckIntervalMs +
                ", initialReconnectDelayMs=" + initialReconnectDelayMs +
                ", maxReconnectDelayMs=" + maxReconnectDelayMs +
                ", reconnectMultiplier=" + reconnectMultiplier +
                ", jitterFactor=" + jitterFactor +
                '}';
    }
}
