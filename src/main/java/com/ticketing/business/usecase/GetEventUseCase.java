package com.ticketing.business.usecase;

import com.ticketing.domain.data.EventData;
import reactor.core.publisher.Mono;

public interface GetEventUseCase {
    Mono<EventData> execute(String eventId);
}
