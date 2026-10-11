package com.ticketing.business.usecase;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.internal_request.CreateEventRequest;
import reactor.core.publisher.Mono;

/**
 * Creates an event, generates all of its tickets and puts it on sale.
 */
public interface CreateEventUseCase {

    /**
     * Validates the request, creates the event in {@code PUBLISHING}, writes {@code totalCapacity} available tickets
     * and moves the event to {@code ON_SALE}.
     *
     * @param request the event definition
     * @return the created event in {@code ON_SALE}; error signal {@code DomainValidationException} when a rule is
     *         violated
     */
    Mono<EventData> execute(CreateEventRequest request);
}
