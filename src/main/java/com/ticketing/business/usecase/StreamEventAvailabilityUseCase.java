package com.ticketing.business.usecase;

import com.ticketing.domain.data.AvailabilitySnapshot;
import reactor.core.publisher.Flux;

/**
 * Streams the availability of an event in near real time.
 */
public interface StreamEventAvailabilityUseCase {

    /**
     * Polls the availability at the configured interval and emits a snapshot only when the counters change
     * ({@link AvailabilitySnapshot#sameCountsAs(AvailabilitySnapshot)}); infinite until cancelled.
     *
     * @param eventId the event identifier
     * @return the snapshots; error signal {@code EventNotFoundException} when the event does not exist
     */
    Flux<AvailabilitySnapshot> execute(String eventId);
}
