package com.ticketing.persistence;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.PageData;
import com.ticketing.enums.EventStatusEnum;
import java.time.Instant;
import reactor.core.publisher.Mono;

/**
 * Access to the {@code events} table.
 */
public interface EventPersistence {

    /**
     * Inserts a new event with the condition {@code attribute_not_exists(eventId)}.
     *
     * @param event the event to insert
     * @return the inserted event; error signal when the item already exists or the write fails
     */
    Mono<EventData> create(EventData event);

    /**
     * Reads an event with a strongly consistent read.
     *
     * @param eventId the event identifier
     * @return the event, or empty when it does not exist
     */
    Mono<EventData> findById(String eventId);

    /**
     * Lists events of a status through the {@code status-eventDate-index} GSI, ordered by event date.
     *
     * @param status the status filter
     * @param limit  maximum items of the page
     * @param cursor opaque Base64 cursor returned by a previous page, {@code null} for the first page
     * @return the page; error signal {@code DomainValidationException} when the cursor is malformed
     */
    Mono<PageData<EventData>> findByStatus(EventStatusEnum status, int limit, String cursor);

    /**
     * Conditionally changes the status of an event ({@code #status = :expected}).
     *
     * @param eventId  the event identifier
     * @param expected the status the event must currently have
     * @param target   the new status
     * @param now      update instant
     * @return {@code true} when updated, {@code false} when the condition failed
     */
    Mono<Boolean> updateStatus(String eventId, EventStatusEnum expected, EventStatusEnum target, Instant now);
}
