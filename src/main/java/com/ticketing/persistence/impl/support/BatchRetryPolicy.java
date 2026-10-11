package com.ticketing.persistence.impl.support;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded exponential backoff for re-submitting the {@code UnprocessedItems} / {@code UnprocessedKeys} of DynamoDB
 * batch operations (partial results under throttling).
 *
 * @param maxAttempts total calls allowed for one batch, including the first one ({@code >= 1})
 * @param firstDelay  delay before the first re-submission ({@code > 0})
 * @param maxDelay    upper bound of a delay ({@code >= firstDelay})
 */
public record BatchRetryPolicy(int maxAttempts, Duration firstDelay, Duration maxDelay) {

    /** Policy used by the adapters: 8 calls, 50 ms doubling up to 2 s (about 4.5 s in the worst case). */
    public static final BatchRetryPolicy DEFAULT = new BatchRetryPolicy(8, Duration.ofMillis(50), Duration.ofSeconds(2));

    /**
     * Validates the settings.
     */
    public BatchRetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        Objects.requireNonNull(firstDelay, "firstDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (firstDelay.isNegative() || firstDelay.isZero() || maxDelay.compareTo(firstDelay) < 0) {
            throw new IllegalArgumentException("delays must satisfy 0 < firstDelay <= maxDelay");
        }
    }

    /**
     * Whether another call is allowed after {@code attemptsMade} calls.
     *
     * @param attemptsMade calls already made for the batch
     * @return {@code true} while {@code attemptsMade < maxAttempts}
     */
    public boolean canRetry(int attemptsMade) {
        return attemptsMade < maxAttempts;
    }

    /**
     * Delay before the next call.
     *
     * @param attemptsMade calls already made for the batch ({@code >= 1})
     * @return {@code firstDelay * 2^(attemptsMade - 1)}, capped at {@code maxDelay}
     */
    public Duration delayAfter(int attemptsMade) {
        int exponent = Math.clamp(attemptsMade - 1L, 0, 30);
        Duration delay = firstDelay.multipliedBy(1L << exponent);
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}
