package com.ticketing.business.usecase.impl.event;

import com.ticketing.business.usecase.GetEventUseCase;
import com.ticketing.cache.EventCacheService;
import com.ticketing.domain.data.EventData;
import com.ticketing.domain.exception.EventNotFoundException;
import com.ticketing.persistence.EventPersistence;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Reads an event through the short-lived event cache (read-mostly API path; inventory decisions never use it).
 */
@Service
public class GetEventUseCaseImpl implements GetEventUseCase {

    private final EventCacheService eventCacheService;
    private final EventPersistence eventPersistence;

    /**
     * Creates the use case.
     *
     * @param eventCacheService event cache
     * @param eventPersistence  events table (cache loader)
     */
    public GetEventUseCaseImpl(EventCacheService eventCacheService, EventPersistence eventPersistence) {
        this.eventCacheService = eventCacheService;
        this.eventPersistence = eventPersistence;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<EventData> execute(String eventId) {
        return Mono.defer(() -> eventCacheService.get(eventId, eventPersistence::findById))
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId)));
    }
}
