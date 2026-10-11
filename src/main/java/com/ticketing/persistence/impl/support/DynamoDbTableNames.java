package com.ticketing.persistence.impl.support;

import java.util.Objects;

public record DynamoDbTableNames(String events, String tickets, String orders, String ticketTransitions,
                                 String distributedLocks, int deadlineShards) {
    public DynamoDbTableNames {
        requireName(events, "events");
        requireName(tickets, "tickets");
        requireName(orders, "orders");
        requireName(ticketTransitions, "ticketTransitions");
        requireName(distributedLocks, "distributedLocks");
        if (deadlineShards < 1) {
            throw new IllegalArgumentException("deadlineShards must be at least 1");
        }
    }

    private static void requireName(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
