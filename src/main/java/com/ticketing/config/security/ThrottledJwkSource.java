package com.ticketing.config.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

@Slf4j
public final class ThrottledJwkSource implements Function<SignedJWT, Flux<JWK>> {
    private static final Duration UNTIL_REPLACED = Duration.ofMillis(Long.MAX_VALUE);

    private final Mono<JWKSet> download;
    private final Clock clock;
    private final Duration minRefreshInterval;
    private final AtomicReference<Mono<KeySet>> current;

    public ThrottledJwkSource(Mono<String> jwkSetDocument, Clock clock, Duration minRefreshInterval) {
        Objects.requireNonNull(jwkSetDocument, "jwkSetDocument");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.minRefreshInterval = Objects.requireNonNull(minRefreshInterval, "minRefreshInterval");
        Assert.isTrue(minRefreshInterval.isPositive(), "minRefreshInterval must be positive");
        this.download = jwkSetDocument.<JWKSet>handle(ThrottledJwkSource::parse).single();
        this.current = new AtomicReference<>(Mono.defer(() -> load(clock.instant()))
                .cache(_ -> UNTIL_REPLACED, _ -> Duration.ZERO, () -> Duration.ZERO));
    }

    @Override
    public Flux<JWK> apply(SignedJWT jwt) {
        JWKSelector selector = new JWKSelector(JWKMatcher.forJWSHeader(jwt.getHeader()));
        return Mono.defer(() -> {
                    Mono<KeySet> generation = current.get();
                    return generation.flatMap(keys -> {
                        List<JWK> matches = selector.select(keys.jwkSet());
                        return matches.isEmpty()
                                ? refresh(generation, keys).map(refreshed -> selector.select(refreshed.jwkSet()))
                                : Mono.just(matches);
                    });
                })
                .flatMapIterable(Function.identity());
    }

    private Mono<KeySet> refresh(Mono<KeySet> generation, KeySet stale) {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            if (now.isBefore(stale.loadedAt().plus(minRefreshInterval))) {
                return current.get();
            }
            Mono<KeySet> next = load(now)
                    .onErrorResume(error -> {
                        log.warn("JWK set refresh failed, keeping the previous keys: {}", error.toString());
                        return Mono.just(new KeySet(stale.jwkSet(), now));
                    })
                    .cache();
            return current.compareAndSet(generation, next) ? next : current.get();
        });
    }

    private Mono<KeySet> load(Instant at) {
        return download.map(jwkSet -> new KeySet(jwkSet, at));
    }

    private static void parse(String document, SynchronousSink<JWKSet> sink) {
        try {
            sink.next(JWKSet.parse(document));
        } catch (ParseException e) {
            sink.error(new IllegalStateException("The JWK set document is malformed", e));
        }
    }

    private record KeySet(JWKSet jwkSet, Instant loadedAt) {
    }
}
