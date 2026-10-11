package com.ticketing.cache.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.github.benmanes.caffeine.cache.Ticker;
import com.ticketing.domain.data.EventData;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class EventCacheServiceImplTest {

    /** Reactor context key under which the web filter stores the correlation id. */
    private static final String CORRELATION_ID_KEY = "correlationId";
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(2);
    private static final EventData EVENT = event("e-1");
    private static final Executor SAME_THREAD = Runnable::run;

    /** Manually advanced time source: expiration is deterministic, no sleeping. */
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final AtomicInteger loads = new AtomicInteger();

    private final EventCacheServiceImpl cache =
            new EventCacheServiceImpl(new EventCacheSettings(TTL, 100), ticker, SAME_THREAD);

    private static EventData event(String eventId) {
        return EventData.newPublishing(eventId, "Concert", "Arena", NOW.plus(Duration.ofDays(30)), 100,
                new BigDecimal("50.00"), "USD", NOW);
    }

    private Function<String, Mono<EventData>> loaderReturning(Mono<EventData> result) {
        return _ -> {
            loads.incrementAndGet();
            return result;
        };
    }

    private Function<String, Mono<EventData>> loaderById() {
        return id -> {
            loads.incrementAndGet();
            return Mono.just(event(id));
        };
    }

    @Test
    void shouldLoadOnMissAndServeTheCachedEventOnHit() {
        Function<String, Mono<EventData>> loader = loaderReturning(Mono.just(EVENT));

        StepVerifier.create(cache.get("e-1", loader)).expectNext(EVENT).verifyComplete();
        StepVerifier.create(cache.get("e-1", loader)).expectNext(EVENT).verifyComplete();

        assertThat(loads).hasValue(1);
    }

    @Test
    void shouldPassTheRequestedIdToTheLoader() {
        StepVerifier.create(cache.get("e-7", loaderById()))
                .assertNext(event -> assertThat(event.eventId()).isEqualTo("e-7"))
                .verifyComplete();
    }

    @Test
    void shouldNotCacheAnEmptyLoaderResult() {
        Function<String, Mono<EventData>> loader = loaderReturning(Mono.empty());

        StepVerifier.create(cache.get("missing", loader)).verifyComplete();
        StepVerifier.create(cache.get("missing", loader)).verifyComplete();

        assertThat(loads).hasValue(2);
    }

    @Test
    void shouldNotCacheALoaderError() {
        IllegalStateException failure = new IllegalStateException("dynamodb unavailable");
        Function<String, Mono<EventData>> loader = _ -> loads.incrementAndGet() == 1
                ? Mono.error(failure)
                : Mono.just(EVENT);

        StepVerifier.create(cache.get("e-1", loader))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                .verify();
        StepVerifier.create(cache.get("e-1", loader)).expectNext(EVENT).verifyComplete();

        assertThat(loads).hasValue(2);
    }

    @Test
    void shouldPropagateALoaderThatThrowsAsAnErrorSignal() {
        Function<String, Mono<EventData>> loader = _ -> {
            loads.incrementAndGet();
            throw new IllegalArgumentException("bad id");
        };

        StepVerifier.create(cache.get("e-1", loader)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(cache.get("e-1", loader)).expectError(IllegalArgumentException.class).verify();

        assertThat(loads).hasValue(2);
    }

    @Test
    void shouldExpireEntriesAfterTheTtl() {
        Function<String, Mono<EventData>> loader = loaderReturning(Mono.just(EVENT));
        StepVerifier.create(cache.get("e-1", loader)).expectNext(EVENT).verifyComplete();

        nanos.addAndGet(TTL.toNanos() - 1);
        StepVerifier.create(cache.get("e-1", loader)).expectNext(EVENT).verifyComplete();
        assertThat(loads).hasValue(1);

        nanos.addAndGet(1);
        StepVerifier.create(cache.get("e-1", loader)).expectNext(EVENT).verifyComplete();
        assertThat(loads).hasValue(2);
    }

    @Test
    void shouldInvokeTheLoaderOnceForConcurrentCallersOfTheSameKey() {
        Sinks.One<EventData> pendingLoad = Sinks.one();
        Function<String, Mono<EventData>> loader = loaderReturning(pendingLoad.asMono());

        StepVerifier.create(Mono.zip(cache.get("e-1", loader), cache.get("e-1", loader)))
                .then(() -> {
                    assertThat(loads).hasValue(1);
                    pendingLoad.tryEmitValue(EVENT);
                })
                .assertNext(both -> {
                    assertThat(both.getT1()).isEqualTo(EVENT);
                    assertThat(both.getT2()).isEqualTo(EVENT);
                })
                .verifyComplete();

        assertThat(loads).hasValue(1);
    }

    @Test
    void shouldCoalesceLoadsAcrossThreads() {
        EventCacheServiceImpl realTimeCache =
                new EventCacheServiceImpl(new EventCacheSettings(Duration.ofMinutes(1), 10));
        Function<String, Mono<EventData>> slowLoader = _ -> {
            loads.incrementAndGet();
            return Mono.delay(Duration.ofMillis(50)).thenReturn(EVENT);
        };

        StepVerifier.create(Flux.range(0, 64)
                        .parallel(8)
                        .runOn(Schedulers.parallel())
                        .flatMap(_ -> realTimeCache.get("e-1", slowLoader))
                        .sequential()
                        .collectList())
                .assertNext(events -> assertThat(events).hasSize(64).containsOnly(EVENT))
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        assertThat(loads).hasValue(1);
    }

    @Test
    void shouldNotCancelTheSharedLoadWhenOneCallerCancels() {
        Sinks.One<EventData> pendingLoad = Sinks.one();
        Function<String, Mono<EventData>> loader = loaderReturning(pendingLoad.asMono());
        Disposable impatientCaller = cache.get("e-1", loader).subscribe();

        StepVerifier.create(cache.get("e-1", loader))
                .then(() -> {
                    impatientCaller.dispose();
                    pendingLoad.tryEmitValue(EVENT);
                })
                .expectNext(EVENT)
                .verifyComplete();
        StepVerifier.create(cache.get("e-1", loader)).expectNext(EVENT).verifyComplete();

        assertThat(loads).hasValue(1);
    }

    @Test
    void shouldRunTheSharedLoadWithTheReactorContextOfTheTriggeringCaller() {
        AtomicReference<String> loaderCorrelationId = new AtomicReference<>();
        Function<String, Mono<EventData>> loader = _ -> {
            loads.incrementAndGet();
            return Mono.deferContextual(context -> {
                loaderCorrelationId.set(context.getOrDefault(CORRELATION_ID_KEY, "none"));
                return Mono.just(EVENT);
            });
        };

        StepVerifier.create(cache.get("e-1", loader).contextWrite(Context.of(CORRELATION_ID_KEY, "corr-1")))
                .expectNext(EVENT)
                .verifyComplete();
        StepVerifier.create(cache.get("e-1", loader).contextWrite(Context.of(CORRELATION_ID_KEY, "corr-2")))
                .expectNext(EVENT)
                .verifyComplete();

        assertThat(loaderCorrelationId).hasValue("corr-1");
        assertThat(loads).hasValue(1);
    }

    @Test
    void shouldKeepEachCallersOwnContextDownstreamOfAJoinedLoad() {
        Sinks.One<EventData> pendingLoad = Sinks.one();
        Function<String, Mono<EventData>> loader = loaderReturning(pendingLoad.asMono());
        Mono<String> first = cache.get("e-1", loader)
                .flatMap(_ -> Mono.deferContextual(context -> Mono.just(context.<String>get(CORRELATION_ID_KEY))))
                .contextWrite(Context.of(CORRELATION_ID_KEY, "corr-1"));
        Mono<String> second = cache.get("e-1", loader)
                .flatMap(_ -> Mono.deferContextual(context -> Mono.just(context.<String>get(CORRELATION_ID_KEY))))
                .contextWrite(Context.of(CORRELATION_ID_KEY, "corr-2"));

        StepVerifier.create(Mono.zip(first, second))
                .then(() -> pendingLoad.tryEmitValue(EVENT))
                .assertNext(both -> {
                    assertThat(both.getT1()).isEqualTo("corr-1");
                    assertThat(both.getT2()).isEqualTo("corr-2");
                })
                .verifyComplete();

        assertThat(loads).hasValue(1);
    }

    @Test
    void shouldReloadAfterEviction() {
        Function<String, Mono<EventData>> loader = loaderReturning(Mono.just(EVENT));
        StepVerifier.create(cache.get("e-1", loader)).expectNext(EVENT).verifyComplete();

        cache.evict("e-1");
        cache.evict("never-cached");

        StepVerifier.create(cache.get("e-1", loader)).expectNext(EVENT).verifyComplete();
        assertThat(loads).hasValue(2);
    }

    @Test
    void shouldBoundTheNumberOfCachedEvents() {
        EventCacheServiceImpl tinyCache =
                new EventCacheServiceImpl(new EventCacheSettings(TTL, 1), ticker, SAME_THREAD);
        Function<String, Mono<EventData>> loader = loaderById();

        for (String id : new String[] {"e-1", "e-2", "e-1", "e-2"}) {
            StepVerifier.create(tinyCache.get(id, loader)).expectNextCount(1).verifyComplete();
        }

        assertThat(loads.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void shouldRejectNullArguments() {
        Function<String, Mono<EventData>> loader = loaderReturning(Mono.just(EVENT));
        EventCacheSettings settings = new EventCacheSettings(TTL, 1);

        assertThatNullPointerException().isThrownBy(() -> cache.get(null, loader));
        assertThatNullPointerException().isThrownBy(() -> cache.get("e-1", null));
        assertThatNullPointerException().isThrownBy(() -> cache.evict(null));
        assertThatNullPointerException().isThrownBy(() -> new EventCacheServiceImpl(null));
        assertThatNullPointerException().isThrownBy(() -> new EventCacheServiceImpl(settings, null, SAME_THREAD));
        assertThatNullPointerException().isThrownBy(() -> new EventCacheServiceImpl(settings, ticker, null));
    }
}
