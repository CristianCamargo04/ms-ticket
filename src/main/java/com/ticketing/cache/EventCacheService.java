package com.ticketing.cache;

import com.ticketing.domain.data.EventData;
import java.util.function.Function;
import reactor.core.publisher.Mono;

public interface EventCacheService {
    Mono<EventData> get(String eventId, Function<String, Mono<EventData>> loader);

    void evict(String eventId);
}
