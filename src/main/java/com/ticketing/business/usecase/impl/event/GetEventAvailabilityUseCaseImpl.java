package com.ticketing.business.usecase.impl.event;

import com.ticketing.business.usecase.GetEventAvailabilityUseCase;
import com.ticketing.domain.data.AvailabilitySnapshot;
import com.ticketing.domain.exception.EventNotFoundException;
import com.ticketing.persistence.EventPersistence;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
@Service
public class GetEventAvailabilityUseCaseImpl implements GetEventAvailabilityUseCase {
    private final EventPersistence eventPersistence;
    private final Clock clock;

    @Override
    public Mono<AvailabilitySnapshot> execute(String eventId) {
        return Mono.defer(() -> eventPersistence.findById(eventId))
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId)))
                .map(event -> event.toAvailabilitySnapshot(clock.instant()));
    }
}
