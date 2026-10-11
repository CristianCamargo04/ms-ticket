package com.ticketing.presentation;

import com.ticketing.presentation.dto.TicketTransitionResponseDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import reactor.core.publisher.Flux;

@RequestMapping("/api/v1/events/{eventId}/tickets/{ticketId}/transitions")
public interface TicketHistoryRestController {
    @GetMapping
    Flux<TicketTransitionResponseDto> getTicketHistory(@PathVariable("eventId") String eventId,
                                                       @PathVariable("ticketId") String ticketId);
}
