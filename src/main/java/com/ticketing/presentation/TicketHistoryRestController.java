package com.ticketing.presentation;

import com.ticketing.presentation.dto.TicketTransitionResponseDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import reactor.core.publisher.Flux;

/**
 * REST contract of the ticket audit trail (ADMIN).
 */
@RequestMapping("/api/v1/events/{eventId}/tickets/{ticketId}/transitions")
public interface TicketHistoryRestController {

    /**
     * Reads the transitions of a ticket in chronological order.
     *
     * @param eventId  the event identifier
     * @param ticketId the ticket identifier
     * @return the transitions (empty array when none)
     */
    @GetMapping
    Flux<TicketTransitionResponseDto> getTicketHistory(@PathVariable("eventId") String eventId,
                                                       @PathVariable("ticketId") String ticketId);
}
