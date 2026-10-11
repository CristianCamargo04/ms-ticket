package com.ticketing.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ReactiveRetryUtilTest {

    private static final Duration MIN = Duration.ofMillis(100);
    private static final Duration MAX = Duration.ofSeconds(1);
    private static final Predicate<Throwable> TRANSIENT = TransientFailure.class::isInstance;

    private static final class TransientFailure extends RuntimeException {
        TransientFailure(int attempt) {
            super("transient failure #" + attempt);
        }
    }

    /** Fails with a {@link TransientFailure} for the first {@code failures} subscriptions, then emits "ok". */
    private static Mono<String> failingTimes(int failures, AtomicInteger subscriptions) {
        return Mono.defer(() -> {
            int attempt = subscriptions.incrementAndGet();
            return attempt <= failures ? Mono.error(new TransientFailure(attempt)) : Mono.just("ok");
        });
    }

    @Test
    void shouldRetryRetryableFailuresWithExponentialBackoff() {
        AtomicInteger subscriptions = new AtomicInteger();

        StepVerifier.withVirtualTime(() -> failingTimes(2, subscriptions)
                        .retryWhen(ReactiveRetryUtil.backoff(3, MIN, MAX, 0.0, TRANSIENT)))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(299))
                .thenAwait(Duration.ofMillis(1))
                .expectNext("ok")
                .verifyComplete();

        assertThat(subscriptions).hasValue(3);
    }

    @Test
    void shouldCapBackoffAtMaxBackoff() {
        AtomicInteger subscriptions = new AtomicInteger();

        StepVerifier.withVirtualTime(() -> failingTimes(3, subscriptions)
                        .retryWhen(ReactiveRetryUtil.backoff(3, MIN, Duration.ofMillis(150), 0.0, TRANSIENT)))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(399))
                .thenAwait(Duration.ofMillis(1))
                .expectNext("ok")
                .verifyComplete();

        assertThat(subscriptions).hasValue(4);
    }

    @Test
    void shouldRetryWithJitter() {
        AtomicInteger subscriptions = new AtomicInteger();

        StepVerifier.withVirtualTime(() -> failingTimes(2, subscriptions)
                        .retryWhen(ReactiveRetryUtil.backoff(6, Duration.ofMillis(25), MAX, 0.5, TRANSIENT)))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(5))
                .expectNext("ok")
                .verifyComplete();

        assertThat(subscriptions).hasValue(3);
    }

    @Test
    void shouldPropagateTheOriginalFailureWhenRetriesAreExhausted() {
        AtomicInteger subscriptions = new AtomicInteger();

        StepVerifier.withVirtualTime(() -> failingTimes(Integer.MAX_VALUE, subscriptions)
                        .retryWhen(ReactiveRetryUtil.backoff(2, MIN, MAX, 0.0, TRANSIENT)))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(10))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(TransientFailure.class)
                        .hasMessage("transient failure #3"))
                .verify();

        assertThat(subscriptions).hasValue(3);
    }

    @Test
    void shouldNotRetryFailuresRejectedByTheFilter() {
        AtomicInteger subscriptions = new AtomicInteger();
        IllegalStateException fatal = new IllegalStateException("fatal");

        StepVerifier.create(Mono.defer(() -> {
                    subscriptions.incrementAndGet();
                    return Mono.<String>error(fatal);
                }).retryWhen(ReactiveRetryUtil.backoff(3, MIN, MAX, 0.5, TRANSIENT)))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(fatal))
                .verify();

        assertThat(subscriptions).hasValue(1);
    }

    @Test
    void shouldNotRetryWhenMaxAttemptsIsZero() {
        AtomicInteger subscriptions = new AtomicInteger();

        StepVerifier.create(failingTimes(1, subscriptions)
                        .retryWhen(ReactiveRetryUtil.backoff(0, MIN, MAX, 0.5, TRANSIENT)))
                .expectError(TransientFailure.class)
                .verify();

        assertThat(subscriptions).hasValue(1);
    }

    @Test
    void shouldRejectNegativeMaxAttempts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ReactiveRetryUtil.backoff(-1, MIN, MAX, 0.5, TRANSIENT))
                .withMessage("maxAttempts must be >= 0 but was -1");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN})
    void shouldRejectJitterOutOfRange(double jitter) {
        assertThatIllegalArgumentException().isThrownBy(() -> ReactiveRetryUtil.backoff(1, MIN, MAX, jitter,
                TRANSIENT));
    }

    @Test
    void shouldRejectNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> ReactiveRetryUtil.backoff(1, null, MAX, 0.5, TRANSIENT));
        assertThatNullPointerException().isThrownBy(() -> ReactiveRetryUtil.backoff(1, MIN, null, 0.5, TRANSIENT));
        assertThatNullPointerException().isThrownBy(() -> ReactiveRetryUtil.backoff(1, MIN, MAX, 0.5, null));
    }
}
