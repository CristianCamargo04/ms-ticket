package com.ticketing.client.rest.impl;

import java.time.Duration;
import java.util.Objects;

public record PaymentGatewaySettings(Duration callTimeout, int maxRetries, Duration retryBackoff) {
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
