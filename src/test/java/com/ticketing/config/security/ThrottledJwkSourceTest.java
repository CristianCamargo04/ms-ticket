package com.ticketing.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class ThrottledJwkSourceTest {
    private static final Duration MIN_REFRESH = Duration.ofSeconds(30);
    private static final Instant START = Instant.parse("2026-10-10T10:00:00Z");

    private static RSAKey key1;
    private static RSAKey key2;

    private final AtomicInteger downloads = new AtomicInteger();
    private final Deque<Mono<String>> responses = new ArrayDeque<>();
    private MutableClock clock;
    private ThrottledJwkSource source;

    @BeforeAll
    static void generateKeys() throws JOSEException {
        key1 = new RSAKeyGenerator(2048).keyID("k1").generate();
        key2 = new RSAKeyGenerator(2048).keyID("k2").generate();
    }

    @BeforeEach
    void createSource() {
        clock = new MutableClock(START);
        Mono<String> document = Mono.defer(() -> {
            downloads.incrementAndGet();
            Mono<String> response = responses.poll();
            return response == null ? Mono.error(new IllegalStateException("unexpected download")) : response;
        });
        source = new ThrottledJwkSource(document, clock, MIN_REFRESH);
    }

    private static Mono<String> jwkSet(RSAKey... keys) {
        return Mono.just(new JWKSet(List.<JWK>of(keys)).toPublicJWKSet().toString());
    }

    private static SignedJWT token(String keyId) {
        return new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(),
                new JWTClaimsSet.Builder().subject("customer-1").build());
    }

    private Flux<String> keyIdsFor(String keyId) {
        return source.apply(token(keyId)).map(JWK::getKeyID);
    }

    @Test
    void shouldDownloadTheKeysLazilyOnceAndServeKnownKeysFromMemory() {
        responses.add(jwkSet(key1));
        assertThat(downloads).hasValue(0);

        StepVerifier.create(keyIdsFor("k1")).expectNext("k1").verifyComplete();
        clock.advance(Duration.ofHours(1));
        StepVerifier.create(keyIdsFor("k1")).expectNext("k1").verifyComplete();

        assertThat(downloads).hasValue(1);
    }

    @Test
    void shouldShareTheInFlightDownloadBetweenConcurrentTokens() {
        Sinks.One<String> pending = Sinks.one();
        responses.add(pending.asMono());

        StepVerifier.create(Flux.merge(keyIdsFor("k1"), keyIdsFor("k1")))
                .then(() -> pending.tryEmitValue(new JWKSet(key1).toPublicJWKSet().toString()))
                .expectNext("k1", "k1")
                .verifyComplete();
        assertThat(downloads).hasValue(1);
    }

    @Test
    void shouldRejectUnknownKeyIdsWithoutDownloadingAgainWithinTheThrottleInterval() {
        responses.add(jwkSet(key1));
        StepVerifier.create(keyIdsFor("k1")).expectNext("k1").verifyComplete();

        clock.advance(MIN_REFRESH.minusSeconds(1));
        for (int i = 0; i < 100; i++) {
            StepVerifier.create(keyIdsFor("forged-" + i)).verifyComplete();
        }

        assertThat(downloads).hasValue(1);
    }

    @Test
    void shouldPickUpRotatedKeysOncePerThrottleInterval() {
        responses.add(jwkSet(key1));
        responses.add(jwkSet(key1, key2));
        StepVerifier.create(keyIdsFor("k1")).expectNext("k1").verifyComplete();

        clock.advance(MIN_REFRESH);
        StepVerifier.create(keyIdsFor("k2")).expectNext("k2").verifyComplete();
        StepVerifier.create(keyIdsFor("k1")).expectNext("k1").verifyComplete();
        StepVerifier.create(keyIdsFor("unknown")).verifyComplete();

        assertThat(downloads).hasValue(2);
    }

    @Test
    void shouldJoinTheRefreshStartedByAConcurrentToken() {
        Sinks.One<String> initial = Sinks.one();
        responses.add(initial.asMono());
        responses.add(jwkSet(key1, key2));

        StepVerifier.create(Flux.merge(keyIdsFor("k2"), keyIdsFor("k2")))
                .then(() -> {
                    clock.advance(MIN_REFRESH);
                    initial.tryEmitValue(new JWKSet(key1).toPublicJWKSet().toString());
                })
                .expectNext("k2", "k2")
                .verifyComplete();
        assertThat(downloads).hasValue(2);
    }

    @Test
    void shouldKeepThePreviousKeysWhenARefreshFailsAndThrottleTheNextOne() {
        responses.add(jwkSet(key1));
        responses.add(Mono.error(new IllegalStateException("issuer down")));
        responses.add(jwkSet(key1, key2));
        StepVerifier.create(keyIdsFor("k1")).expectNext("k1").verifyComplete();

        clock.advance(MIN_REFRESH.plusSeconds(1));
        StepVerifier.create(keyIdsFor("k2")).verifyComplete();
        StepVerifier.create(keyIdsFor("k1")).expectNext("k1").verifyComplete();
        StepVerifier.create(keyIdsFor("k2")).verifyComplete();
        assertThat(downloads).hasValue(2);

        clock.advance(MIN_REFRESH);
        StepVerifier.create(keyIdsFor("k2")).expectNext("k2").verifyComplete();
        assertThat(downloads).hasValue(3);
    }

    @Test
    void shouldRetryAFailedFirstDownloadOnTheNextToken() {
        responses.add(Mono.error(new IllegalStateException("issuer down")));
        responses.add(jwkSet(key1));

        StepVerifier.create(keyIdsFor("k1")).verifyErrorMessage("issuer down");
        StepVerifier.create(keyIdsFor("k1")).expectNext("k1").verifyComplete();

        assertThat(downloads).hasValue(2);
    }

    @Test
    void shouldFailOnMalformedOrEmptyDocuments() {
        responses.add(Mono.just("not a jwk set"));
        responses.add(Mono.empty());

        StepVerifier.create(keyIdsFor("k1"))
                .verifyErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("The JWK set document is malformed"));
        StepVerifier.create(keyIdsFor("k1")).verifyError(NoSuchElementException.class);
    }

    @Test
    void shouldValidateItsArguments() {
        Mono<String> document = Mono.empty();
        Clock fixed = Clock.fixed(START, ZoneOffset.UTC);

        assertThatNullPointerException().isThrownBy(() -> new ThrottledJwkSource(null, fixed, MIN_REFRESH));
        assertThatNullPointerException().isThrownBy(() -> new ThrottledJwkSource(document, null, MIN_REFRESH));
        assertThatNullPointerException().isThrownBy(() -> new ThrottledJwkSource(document, fixed, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ThrottledJwkSource(document, fixed, Duration.ZERO))
                .withMessageContaining("minRefreshInterval");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
