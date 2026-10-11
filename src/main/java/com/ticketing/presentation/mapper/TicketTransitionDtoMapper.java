package com.ticketing.presentation.mapper;

import com.ticketing.domain.data.TicketTransitionData;
import com.ticketing.presentation.dto.TicketTransitionResponseDto;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Maps ticket transitions to the audit-trail representation.
 */
@Component
public class TicketTransitionDtoMapper {

    /**
     * Maps one transition.
     *
     * @param transition the transition
     * @return the response item
     */
    public TicketTransitionResponseDto toResponse(TicketTransitionData transition) {
        Objects.requireNonNull(transition, "transition");
        return new TicketTransitionResponseDto(transition.ticketId(), transition.fromStatus(), transition.toStatus(),
                transition.orderId(), transition.reason(), transition.actor(), transition.occurredAt());
    }
}
