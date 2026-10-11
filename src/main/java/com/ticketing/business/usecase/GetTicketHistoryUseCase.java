package com.ticketing.business.usecase;

import com.ticketing.domain.data.TicketTransitionData;
import reactor.core.publisher.Flux;

/**
 * Reads the audit trail of a ticket.
 */
public interface GetTicketHistoryUseCase {

    /**
     * Reads the transitions of a ticket.
     *
     * @param eventId  the event identifier
     * @param ticketId the ticket identifier
     * @return the transitions in ascending chronological order (empty when none)
     */
    Flux<TicketTransitionData> execute(String eventId, String ticketId);
}
