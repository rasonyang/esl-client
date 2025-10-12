package org.freeswitch.esl.client.inbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * Implements exponential backoff strategy with jitter for reconnection attempts.
 * <p>
 * The delay increases exponentially: delay = min(initialDelay * multiplier^attempt, maxDelay)
 * Jitter (randomization) is added to prevent thundering herd problem.
 * <p>
 * Example with initialDelay=1000ms, multiplier=2.0, maxDelay=60000ms, jitter=0.2:
 * <pre>
 * Attempt  |  Calculated Delay  |  With Jitter (±10%)  |  Cumulative Time
 * ---------|--------------------|-----------------------|------------------
 * 1        |  1s                |  0.9s - 1.1s         |  ~1s
 * 2        |  2s                |  1.8s - 2.2s         |  ~3s
 * 3        |  4s                |  3.6s - 4.4s         |  ~7s
 * 4        |  8s                |  7.2s - 8.8s         |  ~15s
 * 5        |  16s               |  14.4s - 17.6s       |  ~31s
 * 6        |  32s               |  28.8s - 35.2s       |  ~63s
 * 7+       |  60s (capped)      |  54s - 66s           |  ~120s+
 * </pre>
 */
public class ExponentialBackoffStrategy {

    private static final Logger log = LoggerFactory.getLogger(ExponentialBackoffStrategy.class);

    private int attemptCount = 0;
    private final long initialDelayMs;
    private final long maxDelayMs;
    private final double multiplier;
    private final double jitterFactor;
    private final Random random;

    /**
     * Creates a new exponential backoff strategy.
     *
     * @param initialDelayMs initial delay in milliseconds
     * @param maxDelayMs     maximum delay in milliseconds
     * @param multiplier     exponential multiplier (must be >= 1.0)
     * @param jitterFactor   jitter factor (0.0 - 1.0)
     */
    public ExponentialBackoffStrategy(long initialDelayMs, long maxDelayMs, double multiplier, double jitterFactor) {
        if (initialDelayMs < 0) {
            throw new IllegalArgumentException("Initial delay cannot be negative: " + initialDelayMs);
        }
        if (maxDelayMs < initialDelayMs) {
            throw new IllegalArgumentException("Max delay must be >= initial delay: " + maxDelayMs + " < " + initialDelayMs);
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("Multiplier must be >= 1.0: " + multiplier);
        }
        if (jitterFactor < 0.0 || jitterFactor > 1.0) {
            throw new IllegalArgumentException("Jitter factor must be between 0.0 and 1.0: " + jitterFactor);
        }

        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.multiplier = multiplier;
        this.jitterFactor = jitterFactor;
        this.random = new Random();

        log.debug("ExponentialBackoffStrategy initialized: initialDelay={}ms, maxDelay={}ms, multiplier={}, jitter={}",
                initialDelayMs, maxDelayMs, multiplier, jitterFactor);
    }

    /**
     * Creates a strategy from a ReconnectionConfig.
     *
     * @param config the reconnection configuration
     * @return new exponential backoff strategy
     */
    public static ExponentialBackoffStrategy fromConfig(ReconnectionConfig config) {
        return new ExponentialBackoffStrategy(
                config.getInitialReconnectDelayMs(),
                config.getMaxReconnectDelayMs(),
                config.getReconnectMultiplier(),
                config.getJitterFactor()
        );
    }

    /**
     * Calculates and returns the next delay for reconnection attempt.
     * Each call increments the attempt counter.
     *
     * @return delay in milliseconds before next reconnection attempt
     */
    public long nextDelay() {
        // Calculate base delay: initialDelay * multiplier^attemptCount
        double baseDelay;
        if (attemptCount == 0) {
            baseDelay = initialDelayMs;
        } else {
            baseDelay = initialDelayMs * Math.pow(multiplier, attemptCount);
        }

        // Cap at max delay
        long cappedDelay = Math.min((long) baseDelay, maxDelayMs);

        // Add jitter: ±(jitterFactor * delay / 2)
        long jitter = (long) (cappedDelay * jitterFactor * (random.nextDouble() - 0.5));
        long finalDelay = Math.max(0, cappedDelay + jitter);

        attemptCount++;

        log.debug("Reconnection attempt {}: base delay={}ms, capped={}ms, jitter={}ms, final={}ms",
                attemptCount, (long) baseDelay, cappedDelay, jitter, finalDelay);

        return finalDelay;
    }

    /**
     * Resets the attempt counter to zero.
     * Should be called after successful reconnection.
     */
    public void reset() {
        log.debug("Resetting backoff strategy (was at attempt {})", attemptCount);
        attemptCount = 0;
    }

    /**
     * Gets the current attempt count.
     *
     * @return number of attempts made since last reset
     */
    public int getAttemptCount() {
        return attemptCount;
    }

    /**
     * Peeks at what the next delay would be without incrementing the attempt counter.
     *
     * @return the delay that would be returned by next call to nextDelay()
     */
    public long peekNextDelay() {
        double baseDelay = attemptCount == 0 ? initialDelayMs : initialDelayMs * Math.pow(multiplier, attemptCount);
        return Math.min((long) baseDelay, maxDelayMs);
    }

    @Override
    public String toString() {
        return "ExponentialBackoffStrategy{" +
                "attemptCount=" + attemptCount +
                ", initialDelayMs=" + initialDelayMs +
                ", maxDelayMs=" + maxDelayMs +
                ", multiplier=" + multiplier +
                ", jitterFactor=" + jitterFactor +
                '}';
    }
}
