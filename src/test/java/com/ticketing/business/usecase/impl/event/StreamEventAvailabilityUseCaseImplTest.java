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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

@ExtendWith(MockitoExtension.class)
class StreamEventAvailabilityUseCaseImplTest {
    private static final Duration INTERVAL = EVENT_POLICY.availabilityStreamInterval();
    private static final String OTHER_EVENT_ID = "33333333-3333-3333-3333-333333333333";

    @Mock
    private GetEventAvailabilityUseCase getEventAvailabilityUseCase;

    @AfterEach
    void resetVirtualTime() {
        VirtualTimeScheduler.reset();
    }

    private StreamEventAvailabilityUseCaseImpl useCase() {
        return new StreamEventAvailabilityUseCaseImpl(getEventAvailabilityUseCase, EVENT_POLICY);
    }

    private static AvailabilitySnapshot snapshot(int available, int secondsAfterNow) {
        return snapshot(EVENT_ID, available, secondsAfterNow);
    }

    private static AvailabilitySnapshot snapshot(String eventId, int available, int secondsAfterNow) {
        return new AvailabilitySnapshot(eventId, 100, available, 100 - available, 0, 0, 0,
                NOW.plusSeconds(secondsAfterNow));
    }

    private AtomicInteger givenATicketSoldBeforeEveryPoll() {
        AtomicInteger lookups = new AtomicInteger();
        when(getEventAvailabilityUseCase.execute(EVENT_ID)).thenAnswer(invocation -> {
            int lookup = lookups.incrementAndGet();
            return Mono.just(snapshot(100 - lookup, lookup));
        });
        return lookups;
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
    void shouldShareOnePollAmongTheSubscribersOfAnEventAndStopItWithTheLastOne() {
        VirtualTimeScheduler time = VirtualTimeScheduler.getOrSet();
        AtomicInteger lookups = givenATicketSoldBeforeEveryPoll();
        StreamEventAvailabilityUseCaseImpl useCase = useCase();
        List<AvailabilitySnapshot> first = new CopyOnWriteArrayList<>();
        List<AvailabilitySnapshot> second = new CopyOnWriteArrayList<>();

        Disposable firstStream = useCase.execute(EVENT_ID).subscribe(first::add);
        time.advanceTimeBy(INTERVAL.multipliedBy(2));
        Disposable secondStream = useCase.execute(EVENT_ID).subscribe(second::add);

        assertThat(second).as("a late subscriber gets the latest snapshot at once, without an extra read")
                .containsExactly(snapshot(97, 3));
        assertThat(lookups).hasValue(3);

        time.advanceTimeBy(INTERVAL.multipliedBy(2));

        assertThat(lookups).as("one read per tick whatever the number of subscribers").hasValue(5);
        assertThat(first).extracting(AvailabilitySnapshot::available).containsExactly(99, 98, 97, 96, 95);
        assertThat(second).extracting(AvailabilitySnapshot::available).containsExactly(97, 96, 95);

        firstStream.dispose();
        time.advanceTimeBy(INTERVAL);

        assertThat(lookups).as("still polled for the remaining subscriber").hasValue(6);
        assertThat(second).extracting(AvailabilitySnapshot::available).containsExactly(97, 96, 95, 94);

        secondStream.dispose();
        time.advanceTimeBy(INTERVAL.multipliedBy(5));

        assertThat(lookups).as("the poll stops with its last subscriber").hasValue(6);

        List<AvailabilitySnapshot> third = new CopyOnWriteArrayList<>();
        Disposable thirdStream = useCase.execute(EVENT_ID).subscribe(third::add);

        assertThat(lookups).as("a new subscriber starts a new poll").hasValue(7);
        assertThat(third).containsExactly(snapshot(93, 7));
        thirdStream.dispose();
    }

    @Test
    void shouldNotLetASlowSubscriberHoldBackTheOthers() {
        VirtualTimeScheduler time = VirtualTimeScheduler.getOrSet();
        givenATicketSoldBeforeEveryPoll();
        StreamEventAvailabilityUseCaseImpl useCase = useCase();
        List<AvailabilitySnapshot> slowReceived = new CopyOnWriteArrayList<>();
        BaseSubscriber<AvailabilitySnapshot> slow = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                request(1);
            }

            @Override
            protected void hookOnNext(AvailabilitySnapshot value) {
                slowReceived.add(value);
            }
        };
        List<AvailabilitySnapshot> fast = new CopyOnWriteArrayList<>();

        useCase.execute(EVENT_ID).subscribe(slow);
        Disposable fastStream = useCase.execute(EVENT_ID).subscribe(fast::add);
        time.advanceTimeBy(INTERVAL.multipliedBy(3));

        assertThat(fast).extracting(AvailabilitySnapshot::available).containsExactly(99, 98, 97, 96);
        assertThat(slowReceived).extracting(AvailabilitySnapshot::available).containsExactly(99);

        slow.request(1);

        assertThat(slowReceived).as("a slow subscriber only gets the latest snapshot, no backlog")
                .extracting(AvailabilitySnapshot::available).containsExactly(99, 96);
        slow.dispose();
        fastStream.dispose();
    }

    @Test
    void shouldStartANewPollForASubscriberArrivingAfterAFailure() {
        AvailabilitySnapshot created = snapshot(100, 0);
        when(getEventAvailabilityUseCase.execute(EVENT_ID))
                .thenReturn(Mono.error(new EventNotFoundException(EVENT_ID))).thenReturn(Mono.just(created));
        StreamEventAvailabilityUseCaseImpl useCase = useCase();

        StepVerifier.withVirtualTime(() -> useCase.execute(EVENT_ID))
                .expectSubscription()
                .expectError(EventNotFoundException.class)
                .verify();
        StepVerifier.withVirtualTime(() -> useCase.execute(EVENT_ID))
                .expectSubscription()
                .expectNext(created)
                .thenCancel()
                .verify();

        verify(getEventAvailabilityUseCase, times(2)).execute(EVENT_ID);
    }

    @Test
    void shouldPollEachEventSeparately() {
        when(getEventAvailabilityUseCase.execute(EVENT_ID)).thenReturn(Mono.just(snapshot(10, 0)));
        when(getEventAvailabilityUseCase.execute(OTHER_EVENT_ID))
                .thenReturn(Mono.just(snapshot(OTHER_EVENT_ID, 20, 0)));
        StreamEventAvailabilityUseCaseImpl useCase = useCase();

        StepVerifier.withVirtualTime(() -> Flux.merge(useCase.execute(EVENT_ID), useCase.execute(OTHER_EVENT_ID)))
                .expectSubscription()
                .expectNextCount(2)
                .thenAwait(INTERVAL)
                .thenCancel()
                .verify();

        verify(getEventAvailabilityUseCase, times(2)).execute(EVENT_ID);
        verify(getEventAvailabilityUseCase, times(2)).execute(OTHER_EVENT_ID);
    }

    @Test
    void shouldNotPollBeforeSubscription() {
        useCase().execute(EVENT_ID);

        verifyNoInteractions(getEventAvailabilityUseCase);
    }
}
