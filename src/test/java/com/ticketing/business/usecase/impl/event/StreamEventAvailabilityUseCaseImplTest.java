package com.ticketing.business.usecase.impl.event;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.EVENT_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.EVENT_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.business.usecase.GetEventAvailabilityUseCase;
import com.ticketing.domain.data.AvailabilitySnapshot;
import com.ticketing.domain.exception.EventNotFoundException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class StreamEventAvailabilityUseCaseImplTest {

    private static final Duration INTERVAL = EVENT_POLICY.availabilityStreamInterval();

    @Mock
    private GetEventAvailabilityUseCase getEventAvailabilityUseCase;

    private StreamEventAvailabilityUseCaseImpl useCase() {
        return new StreamEventAvailabilityUseCaseImpl(getEventAvailabilityUseCase, EVENT_POLICY);
    }

    private static AvailabilitySnapshot snapshot(int available, int secondsAfterNow) {
        return new AvailabilitySnapshot(EVENT_ID, 100, available, 100 - available, 0, 0, 0,
                NOW.plusSeconds(secondsAfterNow));
    }

    @Test
    void shouldEmitImmediatelyThenOnlyWhenTheCountersChange() {
        AvailabilitySnapshot first = snapshot(100, 0);
        AvailabilitySnapshot unchanged = snapshot(100, 1);
        AvailabilitySnapshot changed = snapshot(97, 2);
        when(getEventAvailabilityUseCase.execute(EVENT_ID))
                .thenReturn(Mono.just(first)).thenReturn(Mono.just(unchanged)).thenReturn(Mono.just(changed));

        StepVerifier.withVirtualTime(() -> useCase().execute(EVENT_ID))
                .expectSubscription()
                .expectNext(first)
                .expectNoEvent(INTERVAL)
                .thenAwait(INTERVAL)
                .expectNext(changed)
                .thenAwait(INTERVAL.multipliedBy(3))
                .expectNoEvent(INTERVAL)
                .thenCancel()
                .verify();

        verify(getEventAvailabilityUseCase, times(7)).execute(EVENT_ID);
    }

    @Test
    void shouldTerminateWithTheErrorOfTheFirstLookup() {
        when(getEventAvailabilityUseCase.execute(EVENT_ID))
                .thenReturn(Mono.error(new EventNotFoundException(EVENT_ID)));

        StepVerifier.withVirtualTime(() -> useCase().execute(EVENT_ID))
                .expectSubscription()
                .expectError(EventNotFoundException.class)
                .verify();

        verify(getEventAvailabilityUseCase, times(1)).execute(EVENT_ID);
    }

    @Test
    void shouldTerminateWhenALaterLookupFails() {
        AvailabilitySnapshot first = snapshot(10, 0);
        when(getEventAvailabilityUseCase.execute(EVENT_ID))
                .thenReturn(Mono.just(first)).thenReturn(Mono.error(new IllegalStateException("read failed")));

        StepVerifier.withVirtualTime(() -> useCase().execute(EVENT_ID))
                .expectSubscription()
                .expectNext(first)
                .thenAwait(INTERVAL)
                .expectErrorMessage("read failed")
                .verify();
    }

    @Test
    void shouldDropTicksWhileALookupIsStillRunningInsteadOfOverflowing() {
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        when(getEventAvailabilityUseCase.execute(EVENT_ID)).thenAnswer(invocation -> {
            int lookup = lookups.incrementAndGet();
            return Mono.delay(INTERVAL.multipliedBy(5).plusMillis(500))
                    .thenReturn(snapshot(100 - lookup, lookup))
                    .doOnSubscribe(subscription -> maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max))
                    .doOnSuccess(snapshot -> inFlight.decrementAndGet());
        });

        StepVerifier.withVirtualTime(() -> useCase().execute(EVENT_ID))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(30))
                .expectNextCount(5)
                .thenCancel()
                .verify();

        assertThat(maxInFlight).as("polls never overlap").hasValue(1);
        assertThat(lookups.get()).as("one poll per 5.5s lookup, the other ticks are dropped").isBetween(5, 6);
    }

    @Test
    void shouldNotPollBeforeSubscription() {
        useCase().execute(EVENT_ID);

        verifyNoInteractions(getEventAvailabilityUseCase);
    }
}
