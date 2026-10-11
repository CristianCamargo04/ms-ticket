package com.ticketing.business.usecase.impl.event;

import com.ticketing.business.usecase.GetEventAvailabilityUseCase;
import com.ticketing.domain.data.AvailabilitySnapshot;
import com.ticketing.domain.exception.EventNotFoundException;
import com.ticketing.persistence.EventPersistence;
import java.time.Clock;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Reads the inventory counters of an event with a strongly consistent read (bypassing the event cache).
 */
@Service
public class GetEventAvailabilityUseCaseImpl implements GetEventAvailabilityUseCase {

    private final EventPersistence eventPersistence;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param eventPersistence events table
     * @param clock            time source of the capture instant
     */
    public GetEventAvailabilityUseCaseImpl(EventPersistence eventPersistence, Clock clock) {
        this.eventPersistence = eventPersistence;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<AvailabilitySnapshot> execute(String eventId) {
        return Mono.defer(() -> eventPersistence.findById(eventId))
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId)))
                .map(event -> event.toAvailabilitySnapshot(clock.instant()));
    }
}
