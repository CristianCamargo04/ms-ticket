package com.ticketing.business.usecase.impl.inventory;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CLOCK;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.NOW;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.SWEEPER_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.expiredHoldOrder;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.queuedOrder;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.timedOutPendingOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.client.rest.api.PaymentGatewayChargeApi;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.data.SweepResult;
import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.exception.PaymentGatewayException;
import com.ticketing.domain.inventory.FinalizeSaleCommand;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import com.ticketing.domain.inventory.ReleaseInventoryCommand;
import com.ticketing.domain.inventory.SweeperPolicy;
import com.ticketing.domain.order.PaymentOutcome;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import com.ticketing.persistence.DistributedLockPersistence;
import com.ticketing.persistence.InventoryTransactionPersistence;
import com.ticketing.persistence.OrderPersistence;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
class ReleaseExpiredReservationsUseCaseImplTest {

    private static final String LOCK = ReleaseExpiredReservationsUseCaseImpl.LOCK_NAME;
    private static final String OWNER = SWEEPER_POLICY.instanceId();

    @Mock
    private DistributedLockPersistence distributedLockPersistence;

    @Mock
    private OrderPersistence orderPersistence;

    @Mock
    private InventoryTransactionPersistence inventoryTransactionPersistence;

    @Mock
    private PaymentGatewayChargeApi paymentGatewayChargeApi;

    @Captor
    private ArgumentCaptor<ReleaseInventoryCommand> releaseCommands;

    @Captor
    private ArgumentCaptor<FinalizeSaleCommand> finalizeCommands;

    private ReleaseExpiredReservationsUseCaseImpl useCase(SweeperPolicy policy) {
        return new ReleaseExpiredReservationsUseCaseImpl(distributedLockPersistence, orderPersistence,
                inventoryTransactionPersistence, paymentGatewayChargeApi, policy, CLOCK);
    }

    private PublisherProbe<Void> givenLeaseAcquired(SweeperPolicy policy) {
        PublisherProbe<Void> release = PublisherProbe.empty();
        when(distributedLockPersistence.tryAcquire(LOCK, policy.instanceId(), policy.lease(), NOW))
                .thenReturn(Mono.just(true));
        when(distributedLockPersistence.release(LOCK, policy.instanceId())).thenReturn(release.mono());
        return release;
    }

    @Test
    void shouldReleaseExpiredHoldsReconcileTimedOutPaymentsAndCountEveryOutcome() {
        PublisherProbe<Void> lease = givenLeaseAcquired(SWEEPER_POLICY);
        OrderData released = expiredHoldOrder("h-1", 2);
        OrderData confirmedMeanwhile = expiredHoldOrder("h-2", 1);
        OrderData broken = expiredHoldOrder("h-3", 1);
        OrderData inconsistentCounters = expiredHoldOrder("h-4", 1);
        OrderData captured = timedOutPendingOrder("p-1", 2);
        OrderData declined = timedOutPendingOrder("p-2", 1);
        OrderData neverCharged = timedOutPendingOrder("p-3", 1);
        OrderData gatewayDown = timedOutPendingOrder("p-4", 1);
        OrderData paidMeanwhile = timedOutPendingOrder("p-5", 1);
        OrderData inconsistent = timedOutPendingOrder("p-6", 1);
        when(orderPersistence.findDueOrders(DeadlineTypeEnum.HOLD_EXPIRATION, NOW, SWEEPER_POLICY.batchSize()))
                .thenReturn(Flux.just(released, confirmedMeanwhile, broken, inconsistentCounters));
        when(orderPersistence.findDueOrders(DeadlineTypeEnum.PAYMENT_CONFIRMATION, NOW, SWEEPER_POLICY.batchSize()))
                .thenReturn(Flux.just(captured, declined, neverCharged, gatewayDown, paidMeanwhile, inconsistent));
        Map<String, Mono<PaymentOutcome>> charges = Map.of(
                "p-1", Mono.just(new PaymentOutcome.Approved("pay-1")),
                "p-2", Mono.just(new PaymentOutcome.Declined("insufficient funds")),
                "p-3", Mono.just(new PaymentOutcome.NotFound()),
                "p-4", Mono.error(new PaymentGatewayException("circuit open")),
                "p-5", Mono.just(new PaymentOutcome.Approved("pay-5")),
                "p-6", Mono.just(new PaymentOutcome.NotFound()));
        when(paymentGatewayChargeApi.findCharge(anyString()))
                .thenAnswer(invocation -> charges.get(invocation.<String>getArgument(0)));
        when(inventoryTransactionPersistence.release(any())).thenAnswer(invocation -> {
            OrderData order = invocation.<ReleaseInventoryCommand>getArgument(0).order();
            return switch (order.orderId()) {
                case "h-2" -> Mono.just(new InventoryWriteOutcome.OrderStateChanged());
                case "h-3" -> Mono.error(new InventoryConflictException("throttled"));
                case "h-4" -> Mono.just(new InventoryWriteOutcome.InsufficientInventory());
                case "p-6" -> Mono.just(new InventoryWriteOutcome.StaleTicketSelection());
                default -> Mono.just(new InventoryWriteOutcome.Applied(order));
            };
        });
        when(inventoryTransactionPersistence.finalizeSale(any())).thenAnswer(invocation -> {
            OrderData order = invocation.<FinalizeSaleCommand>getArgument(0).order();
            return "p-5".equals(order.orderId())
                    ? Mono.just(new InventoryWriteOutcome.OrderStateChanged())
                    : Mono.just(new InventoryWriteOutcome.Applied(order));
        });

        StepVerifier.create(useCase(SWEEPER_POLICY).execute())
                .expectNext(new SweepResult(true, 3, 1, 0, 4))
                .verifyComplete();

        lease.assertWasSubscribed();
        verify(inventoryTransactionPersistence, times(7)).release(releaseCommands.capture());
        Map<String, ReleaseInventoryCommand> releases = releaseCommands.getAllValues().stream()
                .collect(Collectors.toMap(command -> command.order().orderId(), Function.identity()));
        assertThat(releases).containsOnlyKeys("h-1", "h-2", "h-3", "h-4", "p-2", "p-3", "p-6");
        assertThat(releases.get("h-1")).satisfies(command -> {
            assertThat(command.expectedStatus()).isEqualTo(TicketStatusEnum.RESERVED);
            assertThat(command.resultingStatus()).isEqualTo(OrderProcessingStatusEnum.EXPIRED);
            assertThat(command.reason()).isEqualTo(TransitionReasonEnum.HOLD_EXPIRED);
            assertThat(command.now()).isEqualTo(NOW);
            assertThat(command.actor()).isEqualTo(LOCK);
        });
        assertThat(releases.get("p-3")).satisfies(command -> {
            assertThat(command.expectedStatus()).isEqualTo(TicketStatusEnum.PENDING_CONFIRMATION);
            assertThat(command.resultingStatus()).isEqualTo(OrderProcessingStatusEnum.EXPIRED);
            assertThat(command.reason()).isEqualTo(TransitionReasonEnum.CONFIRMATION_TIMEOUT);
        });
        verify(inventoryTransactionPersistence, times(2)).finalizeSale(finalizeCommands.capture());
        assertThat(finalizeCommands.getAllValues())
                .extracting(FinalizeSaleCommand::paymentReference)
                .containsExactlyInAnyOrder("pay-1", "pay-5");
        assertThat(finalizeCommands.getAllValues()).allSatisfy(command -> assertThat(command.actor()).isEqualTo(LOCK));
    }

    @Test
    void shouldCountAnOrderWhoseCommandCannotBeBuiltAsFailed() {
        givenLeaseAcquired(SWEEPER_POLICY);
        OrderData corrupt = queuedOrder("h-1", OrderTypeEnum.STANDARD, 1);
        when(orderPersistence.findDueOrders(DeadlineTypeEnum.HOLD_EXPIRATION, NOW, SWEEPER_POLICY.batchSize()))
                .thenReturn(Flux.just(corrupt));
        when(orderPersistence.findDueOrders(DeadlineTypeEnum.PAYMENT_CONFIRMATION, NOW, SWEEPER_POLICY.batchSize()))
                .thenReturn(Flux.empty());

        StepVerifier.create(useCase(SWEEPER_POLICY).execute())
                .expectNext(new SweepResult(true, 0, 0, 0, 1))
                .verifyComplete();

        verifyNoInteractions(inventoryTransactionPersistence);
    }

    @Test
    void shouldProcessDueOrdersWithBoundedConcurrency() {
        SweeperPolicy twoAtATime = new SweeperPolicy(Duration.ofSeconds(30), 100, 2, "instance-1");
        givenLeaseAcquired(twoAtATime);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        when(orderPersistence.findDueOrders(DeadlineTypeEnum.HOLD_EXPIRATION, NOW, 100))
                .thenReturn(Flux.fromIterable(IntStream.rangeClosed(1, 6).mapToObj(i -> expiredHoldOrder("h-" + i, 1))
                        .toList()));
        when(orderPersistence.findDueOrders(DeadlineTypeEnum.PAYMENT_CONFIRMATION, NOW, 100)).thenReturn(Flux.empty());
        when(inventoryTransactionPersistence.release(any())).thenAnswer(invocation -> Mono
                .delay(Duration.ofMillis(100))
                .<InventoryWriteOutcome>thenReturn(new InventoryWriteOutcome.Applied(
                        invocation.<ReleaseInventoryCommand>getArgument(0).order()))
                .doOnSubscribe(subscription -> maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max))
                .doOnSuccess(outcome -> inFlight.decrementAndGet()));

        StepVerifier.withVirtualTime(() -> useCase(twoAtATime).execute())
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(1))
                .expectNext(new SweepResult(true, 6, 0, 0, 0))
                .verifyComplete();

        assertThat(maxInFlight).hasValue(2);
    }

    @Test
    void shouldSkipTheRunWhenAnotherInstanceHoldsTheLease() {
        when(distributedLockPersistence.tryAcquire(LOCK, OWNER, SWEEPER_POLICY.lease(), NOW))
                .thenReturn(Mono.just(false));

        StepVerifier.create(useCase(SWEEPER_POLICY).execute())
                .expectNext(SweepResult.skipped())
                .verifyComplete();

        verifyNoInteractions(orderPersistence, inventoryTransactionPersistence, paymentGatewayChargeApi);
        verify(distributedLockPersistence, never()).release(anyString(), anyString());
    }

    @Test
    void shouldReleaseTheLeaseWhenTheDueOrdersCannotBeRead() {
        PublisherProbe<Void> lease = givenLeaseAcquired(SWEEPER_POLICY);
        when(orderPersistence.findDueOrders(DeadlineTypeEnum.HOLD_EXPIRATION, NOW, SWEEPER_POLICY.batchSize()))
                .thenReturn(Flux.error(new IllegalStateException("index unavailable")));

        StepVerifier.create(useCase(SWEEPER_POLICY).execute())
                .expectErrorMessage("index unavailable")
                .verify();

        lease.assertWasSubscribed();
    }

    @Test
    void shouldBeLazy() {
        useCase(SWEEPER_POLICY).execute();

        verifyNoInteractions(distributedLockPersistence, orderPersistence);
    }
}
