package com.ticketing.business.usecase.impl.event;

import com.ticketing.business.usecase.GetEventUseCase;
import com.ticketing.cache.EventCacheService;
import com.ticketing.domain.data.EventData;
import com.ticketing.domain.exception.EventNotFoundException;
import com.ticketing.persistence.EventPersistence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
@Service
public class GetEventUseCaseImpl implements GetEventUseCase {
    private final EventCacheService eventCacheService;
    private final EventPersistence eventPersistence;

    @Override
    public Mono<EventData> execute(String eventId) {
        return Mono.defer(() -> eventCacheService.get(eventId, eventPersistence::findById))
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId)));
    }
}
