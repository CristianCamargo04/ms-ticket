package com.ticketing.client.sdk.impl;

import java.util.Objects;

/**
 * Names of the SQS queues the order queue client publishes to. Instantiated by the composition root
 * ({@code config}) from {@code aws.sqs.*}.
 *
 * @param orderReservationQueue queue consumed by the reservation workers
 * @param orderPaymentQueue     queue consumed by the payment workers
 */
public record SqsQueueNames(String orderReservationQueue, String orderPaymentQueue) {

    /**
     * Validates that both queue names are present.
     */
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
