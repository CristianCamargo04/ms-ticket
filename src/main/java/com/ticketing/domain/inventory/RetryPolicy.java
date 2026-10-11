package com.ticketing.domain.inventory;

import com.ticketing.domain.validation.DomainPreconditions;
import java.time.Duration;
import java.util.Objects;

/**
 * Exponential backoff settings for retrying transient failures (Reactor {@code Retry.backoff} semantics).
 * Typical use: {@code ReactiveRetryUtil.backoff(p.maxAttempts(), p.minBackoff(), p.maxBackoff(), p.jitter(),
 * TicketingException::isRetryable)}.
 *
 * @param maxAttempts maximum number of retries after the first failure, {@code >= 0}
 * @param minBackoff  first backoff delay, {@code >= 0}
 * @param maxBackoff  upper bound of the delay, {@code >= minBackoff}
 * @param jitter      jitter factor between 0 and 1
 */
public record RetryPolicy(int maxAttempts, Duration minBackoff, Duration maxBackoff, double jitter) {

    /**
     * Validates the settings.
     *
     * @throws IllegalArgumentException when a value is out of range
     */
    public RetryPolicy {
        DomainPreconditions.requireNonNegative(maxAttempts, "maxAttempts");
        DomainPreconditions.requireNonNegative(minBackoff, "minBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        DomainPreconditions.require(maxBackoff.compareTo(minBackoff) >= 0, "maxBackoff must be >= minBackoff");
        DomainPreconditions.requireBetween(jitter, 0.0, 1.0, "jitter");
    }
}
