package com.ticketing.business.usecase.impl.event;

import com.ticketing.business.usecase.GetTicketHistoryUseCase;
import com.ticketing.domain.data.TicketTransitionData;
import com.ticketing.persistence.TicketTransitionPersistence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@RequiredArgsConstructor
@Service
public class GetTicketHistoryUseCaseImpl implements GetTicketHistoryUseCase {
    private final TicketTransitionPersistence ticketTransitionPersistence;

    @Override
    public Flux<TicketTransitionData> execute(String eventId, String ticketId) {
        return Flux.defer(() -> ticketTransitionPersistence.findByTicket(eventId, ticketId));
    }
}
