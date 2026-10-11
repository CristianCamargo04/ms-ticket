package com.ticketing.business.usecase.impl.inventory;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CLOCK;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.EVENT_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.NOW;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.ORDER_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.SWEEPER_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.queuedOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.client.sdk.SqsOrderQueueClient;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.data.SweepResult;
import com.ticketing.domain.exception.MessagePublishingException;
import com.ticketing.domain.internal_request.OrderReservationMessage;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.persistence.DistributedLockPersistence;
import com.ticketing.persistence.OrderPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;

@ExtendWith(MockitoExtension.class)
class RepublishStaleOrdersUseCaseImplTest {

    private static final String LOCK = RepublishStaleOrdersUseCaseImpl.LOCK_NAME;
    private static final String OWNER = SWEEPER_POLICY.instanceId();

    @Mock
    private DistributedLockPersistence distributedLockPersistence;

    @Mock
    private OrderPersistence orderPersistence;

    @Mock
    private SqsOrderQueueClient sqsOrderQueueClient;

    @Captor
    private ArgumentCaptor<OrderReservationMessage> messages;

    private RepublishStaleOrdersUseCaseImpl useCase() {
        return new RepublishStaleOrdersUseCaseImpl(distributedLockPersistence, orderPersistence, sqsOrderQueueClient,
                ORDER_POLICY, SWEEPER_POLICY, CLOCK);
    }

    private PublisherProbe<Void> givenLeaseAcquired() {
        PublisherProbe<Void> release = PublisherProbe.empty();
        when(distributedLockPersistence.tryAcquire(LOCK, OWNER, SWEEPER_POLICY.lease(), NOW))
                .thenReturn(Mono.just(true));
        when(distributedLockPersistence.release(LOCK, OWNER)).thenReturn(release.mono());
        return release;
    }

    private static OrderData stale(String orderId) {
        return queuedOrder(orderId, OrderTypeEnum.STANDARD, 1);
    }

    @Test
    void shouldRepublishStaleOrdersMoveTheirDeadlineAndCountFailures() {
        PublisherProbe<Void> lease = givenLeaseAcquired();
        when(orderPersistence.findDueOrders(DeadlineTypeEnum.DISPATCH, NOW, SWEEPER_POLICY.batchSize()))
                .thenReturn(Flux.just(stale("q-1"), stale("q-2"), stale("q-3"), stale("q-4")));
        when(sqsOrderQueueClient.publishReservationRequested(any())).thenAnswer(invocation ->
                "q-3".equals(invocation.<OrderReservationMessage>getArgument(0).orderId())
                        ? Mono.error(new MessagePublishingException("queue down", new RuntimeException()))
                        : Mono.just("msg"));
        when(orderPersistence.rescheduleDeadline(anyString(), any(), any(), any())).thenAnswer(invocation ->
                switch (invocation.<String>getArgument(0)) {
                    case "q-2" -> Mono.just(false);
                    case "q-4" -> Mono.error(new IllegalStateException("throttled"));
                    default -> Mono.just(true);
                });

        StepVerifier.create(useCase().execute())
                .expectNext(new SweepResult(true, 0, 0, 2, 2))
                .verifyComplete();

        lease.assertWasSubscribed();
        verify(sqsOrderQueueClient, times(4)).publishReservationRequested(messages.capture());
        assertThat(messages.getAllValues()).contains(new OrderReservationMessage("q-1", EVENT_ID, "q-1", NOW));
        verify(orderPersistence).rescheduleDeadline("q-1", OrderProcessingStatusEnum.QUEUED,
                ORDER_POLICY.dispatchDeadline(NOW), NOW);
        verify(orderPersistence, never()).rescheduleDeadline(eq("q-3"), any(), any(), any());
    }

    @Test
    void shouldSkipTheRunWhenAnotherInstanceHoldsTheLease() {
        when(distributedLockPersistence.tryAcquire(LOCK, OWNER, SWEEPER_POLICY.lease(), NOW))
                .thenReturn(Mono.just(false));

        StepVerifier.create(useCase().execute())
                .expectNext(SweepResult.skipped())
                .verifyComplete();

        verifyNoInteractions(orderPersistence, sqsOrderQueueClient);
    }

    @Test
    void shouldReleaseTheLeaseWhenTheDueOrdersCannotBeRead() {
        PublisherProbe<Void> lease = givenLeaseAcquired();
        when(orderPersistence.findDueOrders(DeadlineTypeEnum.DISPATCH, NOW, SWEEPER_POLICY.batchSize()))
                .thenReturn(Flux.error(new IllegalStateException("index unavailable")));

        StepVerifier.create(useCase().execute())
                .expectErrorMessage("index unavailable")
                .verify();

        lease.assertWasSubscribed();
        verifyNoInteractions(sqsOrderQueueClient);
    }

    @Test
    void shouldBeLazy() {
        useCase().execute();

        verifyNoInteractions(distributedLockPersistence, orderPersistence, sqsOrderQueueClient);
    }
}
