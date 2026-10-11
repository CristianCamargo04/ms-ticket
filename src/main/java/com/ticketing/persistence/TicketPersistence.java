package com.ticketing.persistence;

import com.ticketing.domain.data.TicketData;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TicketPersistence {
    Mono<Long> saveAll(Flux<TicketData> tickets);

    Flux<TicketData> findAvailable(String eventId, String fromTicketId, int limit);

    Flux<TicketData> findByIds(String eventId, List<String> ticketIds);
}
