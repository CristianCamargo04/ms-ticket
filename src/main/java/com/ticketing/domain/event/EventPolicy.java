package com.ticketing.domain.event;

import com.ticketing.domain.data.TicketData;
import com.ticketing.domain.validation.DomainPreconditions;
import java.time.Duration;

public record EventPolicy(int maxCapacity, Duration availabilityStreamInterval, int ticketWriteConcurrency) {
    public EventPolicy {
        DomainPreconditions.requireBetween(maxCapacity, 1, TicketData.MAX_SEQUENCE, "maxCapacity");
        DomainPreconditions.requirePositive(availabilityStreamInterval, "availabilityStreamInterval");
        DomainPreconditions.requireBetween(ticketWriteConcurrency, 1, Integer.MAX_VALUE, "ticketWriteConcurrency");
    }
}
