package com.ticketing.persistence;

import com.ticketing.domain.data.TicketTransitionData;
import reactor.core.publisher.Flux;

public interface TicketTransitionPersistence {
    Flux<TicketTransitionData> findByTicket(String eventId, String ticketId);
}
