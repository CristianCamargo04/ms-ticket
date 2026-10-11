package com.ticketing.presentation.impl;

import static org.mockito.BDDMockito.given;

import com.ticketing.business.usecase.ReleaseExpiredReservationsUseCase;
import com.ticketing.domain.data.SweepResult;
import com.ticketing.domain.exception.InventoryConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationSchedulerControllerImplTest {

    @Mock
    private ReleaseExpiredReservationsUseCase releaseExpiredReservationsUseCase;

    private ReservationExpirationSchedulerControllerImpl controller;

    @BeforeEach
    void setUp() {
        controller = new ReservationExpirationSchedulerControllerImpl(releaseExpiredReservationsUseCase);
    }

    @Test
    void shouldReturnTheSweepResult() {
        SweepResult result = new SweepResult(true, 3, 1, 0, 1);
        given(releaseExpiredReservationsUseCase.execute()).willReturn(Mono.just(result));

        StepVerifier.create(controller.execute()).expectNext(result).verifyComplete();
    }

    @Test
    void shouldReturnAnIdleSweep() {
        SweepResult idle = new SweepResult(true, 0, 0, 0, 0);
        given(releaseExpiredReservationsUseCase.execute()).willReturn(Mono.just(idle));

        StepVerifier.create(controller.execute()).expectNext(idle).verifyComplete();
    }

    @Test
    void shouldReturnSkippedWhenAnotherInstanceHoldsTheLease() {
        given(releaseExpiredReservationsUseCase.execute()).willReturn(Mono.just(SweepResult.skipped()));

        StepVerifier.create(controller.execute()).expectNext(SweepResult.skipped()).verifyComplete();
    }

    @Test
    void shouldNeverErrorOut() {
        given(releaseExpiredReservationsUseCase.execute())
                .willReturn(Mono.error(new InventoryConflictException("lock table throttled")));

        StepVerifier.create(controller.execute()).expectNext(SweepResult.skipped()).verifyComplete();
    }

    @Test
    void shouldNeverErrorOutOnSynchronousFailures() {
        given(releaseExpiredReservationsUseCase.execute()).willThrow(new IllegalStateException("bug"));

        StepVerifier.create(controller.execute()).expectNext(SweepResult.skipped()).verifyComplete();
    }

    @Test
    void shouldReportSkippedWhenTheUseCaseCompletesEmpty() {
        given(releaseExpiredReservationsUseCase.execute()).willReturn(Mono.empty());

        StepVerifier.create(controller.execute()).expectNext(SweepResult.skipped()).verifyComplete();
    }
}
