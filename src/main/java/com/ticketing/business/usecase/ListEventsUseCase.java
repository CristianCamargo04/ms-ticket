package com.ticketing.business.usecase;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.PageData;
import com.ticketing.domain.internal_request.ListEventsRequest;
import reactor.core.publisher.Mono;

/**
 * Lists events by status with cursor pagination.
 */
public interface ListEventsUseCase {

    /**
     * Lists one page of events; the limit is clamped to 1..100.
     *
     * @param request status filter, limit and cursor
     * @return the page; error signal {@code DomainValidationException} when the cursor is malformed
     */
    Mono<PageData<EventData>> execute(ListEventsRequest request);
}
