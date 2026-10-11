package com.ticketing.business.usecase;

import com.ticketing.domain.data.TicketTransitionData;
import reactor.core.publisher.Flux;

public interface GetTicketHistoryUseCase {
    Flux<TicketTransitionData> execute(String eventId, String ticketId);
}
