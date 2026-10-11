package com.ticketing.persistence.impl.support;

import java.util.Objects;

/**
 * Physical DynamoDB table names used by the persistence adapters, plus the number of shards of the sparse
 * {@code deadline-index}. Instantiated by the composition root ({@code config}) from {@code aws.dynamodb.*}.
 *
 * @param events            events table name
 * @param tickets           tickets table name
 * @param orders            orders table name
 * @param ticketTransitions ticket transitions (audit) table name
 * @param distributedLocks  distributed locks table name
 * @param deadlineShards    number of {@code deadlinePartition} shards (at least 1)
 */
public record DynamoDbTableNames(String events, String tickets, String orders, String ticketTransitions,
                                 String distributedLocks, int deadlineShards) {

    /**
     * Validates that every name is present and that there is at least one deadline shard.
     */
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
