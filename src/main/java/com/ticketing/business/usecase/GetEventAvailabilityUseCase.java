package com.ticketing.business.usecase;

import com.ticketing.domain.data.AvailabilitySnapshot;
import reactor.core.publisher.Mono;

/**
 * Reads the current inventory counters of an event (strongly consistent read, no cache).
 */
public interface GetEventAvailabilityUseCase {

    /**
     * Reads the availability of an event.
     *
     * @param eventId the event identifier
     * @return the snapshot captured now; error signal {@code EventNotFoundException} when the event does not exist
     */
    Mono<AvailabilitySnapshot> execute(String eventId);
}
