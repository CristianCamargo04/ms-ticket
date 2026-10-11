package com.ticketing.business.usecase.impl.event;

import com.ticketing.business.usecase.ListEventsUseCase;
import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.PageData;
import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.domain.internal_request.ListEventsRequest;
import com.ticketing.persistence.EventPersistence;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Lists one page of events of a status; the page size is clamped to {@value #MIN_LIMIT}..{@value #MAX_LIMIT} (the
 * default page size is applied by the presentation layer).
 */
@Service
public class ListEventsUseCaseImpl implements ListEventsUseCase {

    /** Smallest page size. */
    static final int MIN_LIMIT = 1;

    /** Largest page size. */
    static final int MAX_LIMIT = 100;

    private final EventPersistence eventPersistence;

    /**
     * Creates the use case.
     *
     * @param eventPersistence events table
     */
    public ListEventsUseCaseImpl(EventPersistence eventPersistence) {
        this.eventPersistence = eventPersistence;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<PageData<EventData>> execute(ListEventsRequest request) {
        return Mono.defer(() -> {
            if (request.status() == null) {
                return Mono.error(new DomainValidationException("status is required"));
            }
            int limit = Math.clamp(request.limit(), MIN_LIMIT, MAX_LIMIT);
            return eventPersistence.findByStatus(request.status(), limit, request.cursor());
        });
    }
}
