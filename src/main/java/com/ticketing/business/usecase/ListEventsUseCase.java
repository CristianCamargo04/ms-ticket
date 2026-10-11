package com.ticketing.business.usecase;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.PageData;
import com.ticketing.domain.internal_request.ListEventsRequest;
import reactor.core.publisher.Mono;

public interface ListEventsUseCase {
    Mono<PageData<EventData>> execute(ListEventsRequest request);
}
