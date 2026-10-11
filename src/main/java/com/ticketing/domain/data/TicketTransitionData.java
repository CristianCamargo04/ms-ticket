package com.ticketing.domain.data;

import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;
import java.util.Objects;

public record TicketTransitionData(String eventId, String ticketId, String transitionId, TicketStatusEnum fromStatus,
                                   TicketStatusEnum toStatus, String orderId, TransitionReasonEnum reason,
                                   String actor, Instant occurredAt) {
    public TicketTransitionData {
        DomainPreconditions.requireNonBlank(eventId, "eventId");
        DomainPreconditions.requireNonBlank(ticketId, "ticketId");
        DomainPreconditions.requireNonBlank(transitionId, "transitionId");
        Objects.requireNonNull(fromStatus, "fromStatus");
        Objects.requireNonNull(toStatus, "toStatus");
        DomainPreconditions.requireNonBlank(orderId, "orderId");
        Objects.requireNonNull(reason, "reason");
        DomainPreconditions.requireNonBlank(actor, "actor");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
