package com.ticketing.business.usecase.impl.testing;

import com.ticketing.cache.EventCacheService;
import com.ticketing.domain.data.EventData;
import java.util.function.Function;
import reactor.core.publisher.Mono;

public final class PassThroughEventCacheService implements EventCacheService {
    @Override
    public Mono<EventData> get(String eventId, Function<String, Mono<EventData>> loader) {
        return Mono.defer(() -> loader.apply(eventId));
    }

    @Override
    public void evict(String eventId) {
    }
}
