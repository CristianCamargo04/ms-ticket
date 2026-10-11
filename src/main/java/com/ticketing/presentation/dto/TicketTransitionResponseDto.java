package com.ticketing.presentation.dto;

import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;

/**
 * One entry of the audit trail of a ticket.
 *
 * @param ticketId   ticket identifier
 * @param fromStatus status before the transition
 * @param toStatus   status after the transition
 * @param orderId    order that caused the transition
 * @param reason     business reason
 * @param actor      who triggered it
 * @param occurredAt when it happened
 */
public record TicketTransitionResponseDto(String ticketId, TicketStatusEnum fromStatus, TicketStatusEnum toStatus,
                                          String orderId, TransitionReasonEnum reason, String actor,
                                          Instant occurredAt) {
}
