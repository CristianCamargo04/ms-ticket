package com.ticketing.business.usecase.impl.event;

import com.ticketing.business.usecase.GetTicketHistoryUseCase;
import com.ticketing.domain.data.TicketTransitionData;
import com.ticketing.persistence.TicketTransitionPersistence;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Reads the audit trail of a ticket (written atomically with every transition by the inventory transactions).
 */
@Service
public class GetTicketHistoryUseCaseImpl implements GetTicketHistoryUseCase {

    private final TicketTransitionPersistence ticketTransitionPersistence;

    /**
     * Creates the use case.
     *
     * @param ticketTransitionPersistence ticket transitions table
     */
    public GetTicketHistoryUseCaseImpl(TicketTransitionPersistence ticketTransitionPersistence) {
        this.ticketTransitionPersistence = ticketTransitionPersistence;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Flux<TicketTransitionData> execute(String eventId, String ticketId) {
        return Flux.defer(() -> ticketTransitionPersistence.findByTicket(eventId, ticketId));
    }
}
