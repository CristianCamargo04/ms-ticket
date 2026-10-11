package com.ticketing.business.usecase.impl.inventory;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.NOW;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.SWEEPER_POLICY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.domain.data.SweepResult;
import com.ticketing.persistence.DistributedLockPersistence;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;

@ExtendWith(MockitoExtension.class)
class LeasedSweepTest {

    private static final String LOCK = "test-sweeper";
    private static final String OWNER = SWEEPER_POLICY.instanceId();

    @Mock
    private DistributedLockPersistence distributedLockPersistence;

    private LeasedSweep leasedSweep() {
        return new LeasedSweep(distributedLockPersistence, SWEEPER_POLICY);
    }

    private void givenLease(Mono<Boolean> acquired) {
        when(distributedLockPersistence.tryAcquire(LOCK, OWNER, SWEEPER_POLICY.lease(), NOW)).thenReturn(acquired);
    }

    @Test
    void shouldTallyTheOutcomesAndReleaseTheLease() {
        givenLease(Mono.just(true));
        PublisherProbe<Void> release = PublisherProbe.empty();
        when(distributedLockPersistence.release(LOCK, OWNER)).thenReturn(release.mono());

        StepVerifier.create(leasedSweep().run(LOCK, NOW, () -> Flux.just(SweepItemOutcome.RELEASED,
                        SweepItemOutcome.RELEASED, SweepItemOutcome.FINALIZED, SweepItemOutcome.UNCHANGED,
                        SweepItemOutcome.FAILED)))
                .expectNext(new SweepResult(true, 2, 1, 0, 1))
                .verifyComplete();

        release.assertWasSubscribed();
    }

    @Test
    void shouldReportAnEmptyRunWhenNothingIsDue() {
        givenLease(Mono.just(true));
        when(distributedLockPersistence.release(LOCK, OWNER)).thenReturn(Mono.empty());

        StepVerifier.create(leasedSweep().run(LOCK, NOW, Flux::empty))
                .expectNext(new SweepResult(true, 0, 0, 0, 0))
                .verifyComplete();
    }

    @Test
    void shouldSkipTheRunWhenAnotherInstanceHoldsTheLease() {
        givenLease(Mono.just(false));
        AtomicBoolean worked = new AtomicBoolean();

        StepVerifier.create(leasedSweep().run(LOCK, NOW, () -> {
                    worked.set(true);
                    return Flux.empty();
                }))
                .expectNext(SweepResult.skipped())
                .verifyComplete();

        assertThat(worked).isFalse();
        verify(distributedLockPersistence, never()).release(anyString(), anyString());
    }

    @Test
    void shouldSkipTheRunWhenTheLeaseStorageAnswersNothing() {
        givenLease(Mono.empty());

        StepVerifier.create(leasedSweep().run(LOCK, NOW, Flux::empty))
                .expectNext(SweepResult.skipped())
                .verifyComplete();

        verify(distributedLockPersistence, never()).release(anyString(), anyString());
    }

    @Test
    void shouldReleaseTheLeaseWhenTheWorkFails() {
        givenLease(Mono.just(true));
        PublisherProbe<Void> release = PublisherProbe.empty();
        when(distributedLockPersistence.release(LOCK, OWNER)).thenReturn(release.mono());

        StepVerifier.create(leasedSweep().run(LOCK, NOW, () -> Flux.error(new IllegalStateException("query failed"))))
                .expectErrorMessage("query failed")
                .verify();

        release.assertWasSubscribed();
    }

    @Test
    void shouldReleaseTheLeaseWhenBuildingTheWorkFails() {
        givenLease(Mono.just(true));
        PublisherProbe<Void> release = PublisherProbe.empty();
        when(distributedLockPersistence.release(LOCK, OWNER)).thenReturn(release.mono());

        StepVerifier.create(leasedSweep().run(LOCK, NOW, () -> {
                    throw new IllegalStateException("bad work");
                }))
                .expectErrorMessage("bad work")
                .verify();

        release.assertWasSubscribed();
    }

    @Test
    void shouldReleaseTheLeaseWhenTheRunIsCancelled() {
        givenLease(Mono.just(true));
        PublisherProbe<Void> release = PublisherProbe.empty();
        when(distributedLockPersistence.release(LOCK, OWNER)).thenReturn(release.mono());
        AtomicBoolean started = new AtomicBoolean();

        StepVerifier.create(leasedSweep().run(LOCK, NOW, () -> {
                    started.set(true);
                    return Flux.never();
                }))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(20))
                .thenCancel()
                .verify();

        assertThat(started).isTrue();
        release.assertWasSubscribed();
    }

    @Test
    void shouldIgnoreAFailedLeaseRelease() {
        givenLease(Mono.just(true));
        when(distributedLockPersistence.release(LOCK, OWNER))
                .thenReturn(Mono.error(new IllegalStateException("network")));

        StepVerifier.create(leasedSweep().run(LOCK, NOW, () -> Flux.just(SweepItemOutcome.REPUBLISHED)))
                .expectNext(new SweepResult(true, 0, 0, 1, 0))
                .verifyComplete();
    }

    @Test
    void shouldIgnoreALeaseReleaseThatThrows() {
        givenLease(Mono.just(true));
        when(distributedLockPersistence.release(LOCK, OWNER)).thenThrow(new IllegalStateException("bug"));

        StepVerifier.create(leasedSweep().run(LOCK, NOW, Flux::empty))
                .expectNext(new SweepResult(true, 0, 0, 0, 0))
                .verifyComplete();
    }

    @Test
    void shouldPropagateALeaseAcquisitionFailure() {
        givenLease(Mono.error(new IllegalStateException("table missing")));

        StepVerifier.create(leasedSweep().run(LOCK, NOW, Flux::empty))
                .expectErrorMessage("table missing")
                .verify();

        verify(distributedLockPersistence, never()).release(anyString(), anyString());
    }

    @Test
    void shouldBeLazy() {
        leasedSweep().run(LOCK, NOW, Flux::empty);

        verifyNoInteractions(distributedLockPersistence);
    }
}
