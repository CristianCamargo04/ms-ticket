package com.ticketing.domain.event;

import com.ticketing.domain.data.TicketData;
import com.ticketing.domain.validation.DomainPreconditions;
import java.time.Duration;

/**
 * Business rules of events (instantiated as a bean by the composition root from {@code ticketing.events.*}).
 *
 * @param maxCapacity                maximum tickets per event (default 20000), at most
 *                                   {@link TicketData#MAX_SEQUENCE}
 * @param availabilityStreamInterval polling interval of the availability stream (default 1s)
 * @param ticketWriteConcurrency     parallel batch writes when generating the tickets of an event (default 4)
 */
public record EventPolicy(int maxCapacity, Duration availabilityStreamInterval, int ticketWriteConcurrency) {

    /**
     * Validates the settings.
     *
     * @throws IllegalArgumentException when a value is out of range
     */
    public EventPolicy {
        DomainPreconditions.requireBetween(maxCapacity, 1, TicketData.MAX_SEQUENCE, "maxCapacity");
        DomainPreconditions.requirePositive(availabilityStreamInterval, "availabilityStreamInterval");
        DomainPreconditions.requireBetween(ticketWriteConcurrency, 1, Integer.MAX_VALUE, "ticketWriteConcurrency");
    }
}
