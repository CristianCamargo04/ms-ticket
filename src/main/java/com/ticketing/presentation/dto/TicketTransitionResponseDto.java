package com.ticketing.presentation.dto;

import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;

public record TicketTransitionResponseDto(String ticketId, TicketStatusEnum fromStatus, TicketStatusEnum toStatus,
                                          String orderId, TransitionReasonEnum reason, String actor,
                                          Instant occurredAt) {
}
