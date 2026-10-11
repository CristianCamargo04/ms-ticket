package com.ticketing.domain.inventory;

import com.ticketing.domain.validation.DomainPreconditions;
import java.time.Duration;
import java.util.Objects;

public record RetryPolicy(int maxAttempts, Duration minBackoff, Duration maxBackoff, double jitter) {
    public RetryPolicy {
        DomainPreconditions.requireNonNegative(maxAttempts, "maxAttempts");
        DomainPreconditions.requireNonNegative(minBackoff, "minBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        DomainPreconditions.require(maxBackoff.compareTo(minBackoff) >= 0, "maxBackoff must be >= minBackoff");
        DomainPreconditions.requireBetween(jitter, 0.0, 1.0, "jitter");
    }
}
