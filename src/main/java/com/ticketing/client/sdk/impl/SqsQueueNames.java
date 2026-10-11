package com.ticketing.client.sdk.impl;

import java.util.Objects;

public record SqsQueueNames(String orderReservationQueue, String orderPaymentQueue) {
    public SqsQueueNames {
        requireName(orderReservationQueue, "orderReservationQueue");
        requireName(orderPaymentQueue, "orderPaymentQueue");
    }

    private static void requireName(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
