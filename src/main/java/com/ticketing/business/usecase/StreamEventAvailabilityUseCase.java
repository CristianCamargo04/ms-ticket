package com.ticketing.business.usecase;

import com.ticketing.domain.data.AvailabilitySnapshot;
import reactor.core.publisher.Flux;

public interface StreamEventAvailabilityUseCase {
    Flux<AvailabilitySnapshot> execute(String eventId);
}
