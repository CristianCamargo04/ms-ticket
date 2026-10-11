package com.ticketing.persistence;

import com.ticketing.domain.data.TicketData;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Access to the {@code tickets} table.
 */
public interface TicketPersistence {

    /**
     * Writes tickets with {@code BatchWriteItem} in chunks of 25, retrying {@code UnprocessedItems} with backoff.
     *
     * @param tickets the tickets to write
     * @return the number of tickets written; error signal when items remain unprocessed after the retries
     */
    Mono<Long> saveAll(Flux<TicketData> tickets);

    /**
     * Reads available tickets through the {@code eventId-statusKey-index} GSI ({@code statusKey BETWEEN
     * "AVAILABLE#" + fromTicketId AND "AVAILABLE$"}). The index is eventually consistent: callers must tolerate
     * stale results (the conditional transaction is the source of truth).
     *
     * @param eventId      the event identifier
     * @param fromTicketId first ticket id of the scan (inclusive); {@code ""} scans from the beginning
     * @param limit        maximum tickets returned
     * @return available tickets in ticket id order
     */
    Flux<TicketData> findAvailable(String eventId, String fromTicketId, int limit);

    /**
     * Reads tickets by id.
     *
     * @param eventId   the event identifier
     * @param ticketIds the ticket identifiers
     * @return the existing tickets (missing ids are skipped)
     */
    Flux<TicketData> findByIds(String eventId, List<String> ticketIds);
}
