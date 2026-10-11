package com.ticketing.client.rest.impl;

import java.time.Duration;
import java.util.Objects;

/**
 * Resilience settings of the payment gateway REST client. The {@code WebClient} itself (base URL, connect and
 * response timeouts) is built by the composition root; these values drive the per-call policy.
 *
 * @param callTimeout  overall timeout of a single call, including the response body
 * @param maxRetries   retries on 5xx responses and timeouts before giving up (0 disables retries)
 * @param retryBackoff first backoff between retries (exponential with jitter)
 */
public record PaymentGatewaySettings(Duration callTimeout, int maxRetries, Duration retryBackoff) {

    /**
     * Validates positive durations and a non-negative retry count.
     */
    public PaymentGatewaySettings {
        requirePositive(callTimeout, "callTimeout");
        requirePositive(retryBackoff, "retryBackoff");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
