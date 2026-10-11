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

@Component
public final class EventCacheServiceImpl implements EventCacheService {
    private final AsyncCache<String, EventData> cache;

    @Autowired
    public EventCacheServiceImpl(EventCacheSettings settings) {
        this(settings, Ticker.systemTicker(), ForkJoinPool.commonPool());
    }

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
        return Mono.deferContextual(context -> Mono.fromFuture(
                () -> cache.get(eventId, (id, _) -> loader.apply(id).contextWrite(context).toFuture()), true));
    }

    @Override
    public void evict(String eventId) {
        cache.synchronous().invalidate(Objects.requireNonNull(eventId, "eventId"));
    }
}
