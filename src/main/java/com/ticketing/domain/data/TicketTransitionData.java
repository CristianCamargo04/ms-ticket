package com.ticketing.domain.data;

import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;
import java.util.Objects;

/**
 * Audit record of one ticket transition, written in the same transaction as the transition itself.
 *
 * @param eventId      event identifier
 * @param ticketId     ticket identifier
 * @param transitionId unique identifier of the transition
 * @param fromStatus   status before the transition
 * @param toStatus     status after the transition
 * @param orderId      order that caused the transition
 * @param reason       business reason
 * @param actor        who triggered it (customer subject, admin subject or a worker name)
 * @param occurredAt   when it happened
 */
public record TicketTransitionData(String eventId, String ticketId, String transitionId, TicketStatusEnum fromStatus,
                                   TicketStatusEnum toStatus, String orderId, TransitionReasonEnum reason,
                                   String actor, Instant occurredAt) {

    /**
     * Validates that every component is present.
     */
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
