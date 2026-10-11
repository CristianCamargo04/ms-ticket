package com.ticketing.cache;

import com.ticketing.domain.data.EventData;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * Short-lived cache of events (Caffeine {@code AsyncCache}) for the read-mostly API paths. Inventory decisions never
 * rely on it: they use consistent reads.
 */
public interface EventCacheService {

    /**
     * Returns the cached event or loads it; concurrent loads of the same id are coalesced. An empty loader result is
     * not cached; a loader error is propagated and not cached.
     *
     * @param eventId the event identifier
     * @param loader  loads the event when it is not cached (e.g. {@code eventPersistence::findById})
     * @return the event, or empty when the loader returns empty
     */
    Mono<EventData> get(String eventId, Function<String, Mono<EventData>> loader);

    /**
     * Removes an event from the cache.
     *
     * @param eventId the event identifier
     */
    void evict(String eventId);
}
