package com.ticketing.util;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

public final class ReactiveRetryUtil {
    private ReactiveRetryUtil() {
    }

    public static RetryBackoffSpec backoff(int maxAttempts, Duration minBackoff, Duration maxBackoff, double jitter,
                                           Predicate<Throwable> retryable) {
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must be >= 0 but was " + maxAttempts);
        }
        if (!(jitter >= 0.0 && jitter <= 1.0)) {
            throw new IllegalArgumentException("jitter must be between 0 and 1 but was " + jitter);
        }
        Objects.requireNonNull(minBackoff, "minBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        Objects.requireNonNull(retryable, "retryable");
        return Retry.backoff(maxAttempts, minBackoff)
                .maxBackoff(maxBackoff)
                .jitter(jitter)
                .filter(retryable)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }
}
