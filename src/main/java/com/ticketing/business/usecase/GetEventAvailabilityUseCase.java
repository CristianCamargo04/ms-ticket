package com.ticketing.business.usecase;

import com.ticketing.domain.data.AvailabilitySnapshot;
import reactor.core.publisher.Mono;

public interface GetEventAvailabilityUseCase {
    Mono<AvailabilitySnapshot> execute(String eventId);
}
