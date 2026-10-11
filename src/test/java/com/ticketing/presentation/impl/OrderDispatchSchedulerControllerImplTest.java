package com.ticketing.presentation.impl;

import static org.mockito.BDDMockito.given;

import com.ticketing.business.usecase.RepublishStaleOrdersUseCase;
import com.ticketing.domain.data.SweepResult;
import com.ticketing.domain.exception.MessagePublishingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class OrderDispatchSchedulerControllerImplTest {

    @Mock
    private RepublishStaleOrdersUseCase republishStaleOrdersUseCase;

    private OrderDispatchSchedulerControllerImpl controller;

    @BeforeEach
    void setUp() {
        controller = new OrderDispatchSchedulerControllerImpl(republishStaleOrdersUseCase);
    }

    @Test
    void shouldReturnTheSweepResult() {
        SweepResult result = new SweepResult(true, 0, 0, 4, 1);
        given(republishStaleOrdersUseCase.execute()).willReturn(Mono.just(result));

        StepVerifier.create(controller.execute()).expectNext(result).verifyComplete();
    }

    @Test
    void shouldReturnAnIdleSweep() {
        SweepResult idle = new SweepResult(true, 0, 0, 0, 0);
        given(republishStaleOrdersUseCase.execute()).willReturn(Mono.just(idle));

        StepVerifier.create(controller.execute()).expectNext(idle).verifyComplete();
    }

    @Test
    void shouldReturnSkippedWhenAnotherInstanceHoldsTheLease() {
        given(republishStaleOrdersUseCase.execute()).willReturn(Mono.just(SweepResult.skipped()));

        StepVerifier.create(controller.execute()).expectNext(SweepResult.skipped()).verifyComplete();
    }

    @Test
    void shouldNeverErrorOut() {
        given(republishStaleOrdersUseCase.execute())
                .willReturn(Mono.error(new MessagePublishingException("queue unavailable", null)));

        StepVerifier.create(controller.execute()).expectNext(SweepResult.skipped()).verifyComplete();
    }

    @Test
    void shouldNeverErrorOutOnSynchronousFailures() {
        given(republishStaleOrdersUseCase.execute()).willThrow(new IllegalStateException("bug"));

        StepVerifier.create(controller.execute()).expectNext(SweepResult.skipped()).verifyComplete();
    }

    @Test
    void shouldReportSkippedWhenTheUseCaseCompletesEmpty() {
        given(republishStaleOrdersUseCase.execute()).willReturn(Mono.empty());

        StepVerifier.create(controller.execute()).expectNext(SweepResult.skipped()).verifyComplete();
    }
}
