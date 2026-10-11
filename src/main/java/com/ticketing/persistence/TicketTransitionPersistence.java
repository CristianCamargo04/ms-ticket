package com.ticketing.persistence;

import com.ticketing.domain.data.TicketTransitionData;
import reactor.core.publisher.Flux;

/**
 * Read access to the {@code ticket-transitions} audit table (written by {@link InventoryTransactionPersistence}).
 */
public interface TicketTransitionPersistence {

    /**
     * Reads the transition history of a ticket.
     *
     * @param eventId  the event identifier
     * @param ticketId the ticket identifier
     * @return the transitions in ascending chronological order (empty when none)
     */
    Flux<TicketTransitionData> findByTicket(String eventId, String ticketId);
}
