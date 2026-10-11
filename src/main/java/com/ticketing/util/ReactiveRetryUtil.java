package com.ticketing.util;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

/**
 * Reactor retry helpers. Takes primitives on purpose: {@code util} must not depend on the domain, so callers unpack
 * their {@code RetryPolicy}.
 */
public final class ReactiveRetryUtil {

    private ReactiveRetryUtil() {
    }

    /**
     * Exponential backoff with jitter that only retries the failures accepted by {@code retryable}. When the retries
     * are exhausted the <em>last original failure</em> is propagated (not Reactor's {@code RetryExhaustedException}),
     * so callers can still map business exceptions. Delays run on {@code Schedulers.parallel()} (virtual time in
     * tests).
     *
     * @param maxAttempts maximum retries after the first failure, {@code >= 0}
     * @param minBackoff  first delay
     * @param maxBackoff  upper bound of the delay
     * @param jitter      jitter factor between 0 and 1
     * @param retryable   which failures are retried; others propagate immediately
     * @return the retry spec, to be used with {@code retryWhen}
     * @throws IllegalArgumentException when {@code maxAttempts < 0} or the jitter is out of range
     */
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
