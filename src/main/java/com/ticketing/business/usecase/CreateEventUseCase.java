package com.ticketing.business.usecase;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.internal_request.CreateEventRequest;
import reactor.core.publisher.Mono;

public interface CreateEventUseCase {
    Mono<EventData> execute(CreateEventRequest request);
}
