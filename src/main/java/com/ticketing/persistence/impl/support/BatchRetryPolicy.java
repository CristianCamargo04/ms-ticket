package com.ticketing.persistence.impl.support;

import java.time.Duration;
import java.util.Objects;

public record BatchRetryPolicy(int maxAttempts, Duration firstDelay, Duration maxDelay) {
    public static final BatchRetryPolicy DEFAULT = new BatchRetryPolicy(8, Duration.ofMillis(50), Duration.ofSeconds(2));

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

    public boolean canRetry(int attemptsMade) {
        return attemptsMade < maxAttempts;
    }

    public Duration delayAfter(int attemptsMade) {
        int exponent = Math.clamp(attemptsMade - 1L, 0, 30);
        Duration delay = firstDelay.multipliedBy(1L << exponent);
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}
