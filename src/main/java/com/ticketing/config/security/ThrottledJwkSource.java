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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

/**
 * Verification keys of the JWT decoder, downloaded from the issuer's JWK set endpoint with a bounded number of calls.
 *
 * <p>Spring Security's remote JWK source downloads the key set again for every token whose key id ({@code kid}) it
 * does not know, so any anonymous caller could make the service call the issuer once per forged bearer token. This
 * source instead:
 * <ul>
 *   <li>downloads the key set lazily on the first token; concurrent requests share the in-flight download, and a
 *       failed first download is not cached (the next token retries it);</li>
 *   <li>serves known keys from memory;</li>
 *   <li>on an unknown key id (key rotation) downloads the key set again at most once per {@code minRefreshInterval}
 *       measured from the previous download; in between, such tokens find no key and are rejected without any
 *       call;</li>
 *   <li>keeps the previous keys when a refresh fails, so a transient issuer outage never invalidates valid tokens.</li>
 * </ul>
 */
public final class ThrottledJwkSource implements Function<SignedJWT, Flux<JWK>> {

    /** {@link Mono#cache} time-to-live meaning "until replaced" (Reactor treats this exact value as infinite). */
    private static final Duration UNTIL_REPLACED = Duration.ofMillis(Long.MAX_VALUE);

    private static final Logger log = LoggerFactory.getLogger(ThrottledJwkSource.class);

    private final Mono<JWKSet> download;
    private final Clock clock;
    private final Duration minRefreshInterval;
    private final AtomicReference<Mono<KeySet>> current;

    /**
     * Creates the source; nothing is downloaded until the first token is verified.
     *
     * @param jwkSetDocument     cold publisher downloading the JWK set document (one HTTP call per subscription)
     * @param clock              time source of the refresh throttle
     * @param minRefreshInterval minimum time between two downloads triggered by unknown key ids
     * @throws IllegalArgumentException when the interval is not positive
     */
    public ThrottledJwkSource(Mono<String> jwkSetDocument, Clock clock, Duration minRefreshInterval) {
        Objects.requireNonNull(jwkSetDocument, "jwkSetDocument");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.minRefreshInterval = Objects.requireNonNull(minRefreshInterval, "minRefreshInterval");
        Assert.isTrue(minRefreshInterval.isPositive(), "minRefreshInterval must be positive");
        this.download = jwkSetDocument.<JWKSet>handle(ThrottledJwkSource::parse).single();
        this.current = new AtomicReference<>(Mono.defer(() -> load(clock.instant()))
                .cache(_ -> UNTIL_REPLACED, _ -> Duration.ZERO, () -> Duration.ZERO));
    }

    /**
     * Selects the keys matching the token header (key id, algorithm family, use).
     *
     * @param jwt the token being verified
     * @return the candidate keys; empty when none matches (the token is then rejected as invalid)
     */
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

    /**
     * Downloads the key set again unless the given generation is younger than the throttle interval or another
     * request already replaced it; a failed download keeps the stale keys (with a fresh timestamp, so it is
     * throttled as well).
     */
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

    /**
     * A downloaded key set.
     *
     * @param jwkSet   the keys
     * @param loadedAt when the download that produced them started
     */
    private record KeySet(JWKSet jwkSet, Instant loadedAt) {
    }
}
