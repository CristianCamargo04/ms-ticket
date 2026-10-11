package com.ticketing.business.usecase;

import com.ticketing.domain.data.EventData;
import reactor.core.publisher.Mono;

/**
 * Reads an event (through the short-lived event cache).
 */
public interface GetEventUseCase {

    /**
     * Reads an event.
     *
     * @param eventId the event identifier
     * @return the event; error signal {@code EventNotFoundException} when it does not exist
     */
    Mono<EventData> execute(String eventId);
}
