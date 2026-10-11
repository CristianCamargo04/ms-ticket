package com.ticketing.cache.impl;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.ticketing.cache.EventCacheService;
import com.ticketing.domain.data.EventData;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Event cache backed by a Caffeine {@link AsyncCache} that stores the in-flight load as a future.
 *
 * <ul>
 *   <li>Concurrent {@link #get} calls for the same id share one future, so the loader runs once (no cache
 *   stampede on a hot event).</li>
 *   <li>A load that completes empty completes the future with {@code null} and a load that fails completes it
 *   exceptionally; Caffeine removes both, so neither "not found" nor errors are cached.</li>
 *   <li>Entries expire {@link EventCacheSettings#ttl()} after the load completes and the cache holds at most
 *   {@link EventCacheSettings#maxSize()} events.</li>
 *   <li>A caller that cancels (e.g. a client disconnect) never cancels the shared load of the other callers.</li>
 * </ul>
 * The cache never blocks: callers subscribe to the shared future.
 */
@Component
public final class EventCacheServiceImpl implements EventCacheService {

    private final AsyncCache<String, EventData> cache;

    /**
     * Creates the cache with the system ticker and Caffeine's default maintenance executor.
     *
     * @param settings TTL and maximum size
     */
    @Autowired
    public EventCacheServiceImpl(EventCacheSettings settings) {
        this(settings, Ticker.systemTicker(), ForkJoinPool.commonPool());
    }

    /**
     * Creates the cache with an explicit time source and maintenance executor (deterministic tests).
     *
     * @param settings TTL and maximum size
     * @param ticker   time source of the expiration
     * @param executor executor of Caffeine's maintenance work (eviction, removal)
     */
    EventCacheServiceImpl(EventCacheSettings settings, Ticker ticker, Executor executor) {
        Objects.requireNonNull(settings, "settings");
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(settings.ttl())
                .maximumSize(settings.maxSize())
                .ticker(Objects.requireNonNull(ticker, "ticker"))
                .executor(Objects.requireNonNull(executor, "executor"))
                .buildAsync();
    }

    @Override
    public Mono<EventData> get(String eventId, Function<String, Mono<EventData>> loader) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(loader, "loader");
        return Mono.fromFuture(() -> cache.get(eventId, (id, _) -> loader.apply(id).toFuture()), true);
    }

    @Override
    public void evict(String eventId) {
        cache.synchronous().invalidate(Objects.requireNonNull(eventId, "eventId"));
    }
}
