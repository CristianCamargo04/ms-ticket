package com.ticketing.business.usecase.impl.order;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CLOCK;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CUSTOMER_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.NOW;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.ORDER_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.OTHER_CUSTOMER_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.pendingOrder;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.queuedOrder;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.reservedOrder;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.soldOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.exception.InvalidOrderStateException;
import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.exception.OrderNotFoundException;
import com.ticketing.domain.internal_request.CancelOrderRequest;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import com.ticketing.domain.inventory.ReleaseInventoryCommand;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import com.ticketing.persistence.InventoryTransactionPersistence;
import com.ticketing.persistence.OrderPersistence;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class CancelOrderUseCaseImplTest {
    private static final CancelOrderRequest REQUEST = new CancelOrderRequest(ORDER_ID, CUSTOMER_ID);
    private static final String REASON = TransitionReasonEnum.CUSTOMER_CANCELLATION.name();

    @Mock
    private OrderPersistence orderPersistence;

    @Mock
    private InventoryTransactionPersistence inventoryTransactionPersistence;

    @Captor
    private ArgumentCaptor<ReleaseInventoryCommand> command;

    private CancelOrderUseCaseImpl useCase() {
        return new CancelOrderUseCaseImpl(orderPersistence, inventoryTransactionPersistence, CLOCK);
    }

    private static OrderData cancelledQueued() {
        return queuedOrder(2).withProcessingStatus(OrderProcessingStatusEnum.CANCELLED, REASON, NOW);
    }

    private static OrderData released(OrderData reserved) {
        return reserved.withRelease(OrderProcessingStatusEnum.CANCELLED, REASON, NOW);
    }

    @Test
    void shouldCancelAQueuedOrderAndReturnItReloaded() {
        OrderData cancelled = cancelledQueued();
        when(orderPersistence.findById(ORDER_ID))
                .thenReturn(Mono.just(queuedOrder(2)))
                .thenReturn(Mono.just(cancelled));
        when(orderPersistence.transitionProcessingStatus(ORDER_ID, OrderProcessingStatusEnum.QUEUED,
                OrderProcessingStatusEnum.CANCELLED, REASON, NOW)).thenReturn(Mono.just(true));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(cancelled)
                .verifyComplete();

        verifyNoInteractions(inventoryTransactionPersistence);
    }

    @Test
    void shouldReleaseTheTicketsOfAReservedOrder() {
        OrderData reserved = reservedOrder(2);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(reserved));
        when(inventoryTransactionPersistence.release(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.Applied(released(reserved))));

        StepVerifier.create(useCase().execute(REQUEST))
                .assertNext(order -> {
                    assertThat(order.processingStatus()).isEqualTo(OrderProcessingStatusEnum.CANCELLED);
                    assertThat(order.ticketStatus()).isEqualTo(TicketStatusEnum.AVAILABLE);
                })
                .verifyComplete();

        verify(inventoryTransactionPersistence).release(command.capture());
        assertThat(command.getValue().order()).isEqualTo(reserved);
        assertThat(command.getValue().expectedStatus()).isEqualTo(TicketStatusEnum.RESERVED);
        assertThat(command.getValue().resultingStatus()).isEqualTo(OrderProcessingStatusEnum.CANCELLED);
        assertThat(command.getValue().reason()).isEqualTo(TransitionReasonEnum.CUSTOMER_CANCELLATION);
        assertThat(command.getValue().now()).isEqualTo(NOW);
        assertThat(command.getValue().actor()).isEqualTo(CUSTOMER_ID);
    }

    @Test
    void shouldReturnAnAlreadyCancelledOrder() {
        OrderData cancelled = cancelledQueued();
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(cancelled));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(cancelled)
                .verifyComplete();

        verify(orderPersistence, times(1)).findById(ORDER_ID);
        verifyNoInteractions(inventoryTransactionPersistence);
    }

    @Test
    void shouldReleaseTheTicketsWhenTheQueuedOrderWasReservedMeanwhile() {
        OrderData reserved = reservedOrder(2);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(queuedOrder(2))).thenReturn(Mono.just(reserved));
        when(orderPersistence.transitionProcessingStatus(ORDER_ID, OrderProcessingStatusEnum.QUEUED,
                OrderProcessingStatusEnum.CANCELLED, REASON, NOW)).thenReturn(Mono.just(false));
        when(inventoryTransactionPersistence.release(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.Applied(released(reserved))));

        StepVerifier.create(useCase().execute(REQUEST))
                .assertNext(order -> assertThat(order.processingStatus())
                        .isEqualTo(OrderProcessingStatusEnum.CANCELLED))
                .verifyComplete();
    }

    @Test
    void shouldReturnTheOrderWhenAConcurrentCancellationWon() {
        OrderData cancelled = cancelledQueued();
        when(orderPersistence.findById(ORDER_ID))
                .thenReturn(Mono.just(queuedOrder(2)))
                .thenReturn(Mono.just(cancelled));
        when(orderPersistence.transitionProcessingStatus(ORDER_ID, OrderProcessingStatusEnum.QUEUED,
                OrderProcessingStatusEnum.CANCELLED, REASON, NOW)).thenReturn(Mono.just(false));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(cancelled)
                .verifyComplete();
    }

    @Test
    void shouldRejectTheCancellationWhenTheReservedOrderWasConfirmedMeanwhile() {
        OrderData reserved = reservedOrder(2);
        when(orderPersistence.findById(ORDER_ID))
                .thenReturn(Mono.just(reserved))
                .thenReturn(Mono.just(pendingOrder(2)));
        when(inventoryTransactionPersistence.release(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.OrderStateChanged()));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(InvalidOrderStateException.class)
                        .hasMessageContaining("cannot be cancelled"))
                .verify();
    }

    @Test
    void shouldGiveUpWhenTheOrderKeepsChangingConcurrently() {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(queuedOrder(2)));
        when(orderPersistence.transitionProcessingStatus(ORDER_ID, OrderProcessingStatusEnum.QUEUED,
                OrderProcessingStatusEnum.CANCELLED, REASON, NOW)).thenReturn(Mono.just(false));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(InventoryConflictException.class)
                        .hasMessageContaining("retry the cancellation"))
                .verify();

        verify(orderPersistence, times(CancelOrderUseCaseImpl.MAX_REEVALUATIONS + 1))
                .transitionProcessingStatus(ORDER_ID, OrderProcessingStatusEnum.QUEUED,
                        OrderProcessingStatusEnum.CANCELLED, REASON, NOW);
    }

    @Test
    void shouldFailWhenTheCancelledOrderCannotBeReloaded() {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(queuedOrder(2))).thenReturn(Mono.empty());
        when(orderPersistence.transitionProcessingStatus(ORDER_ID, OrderProcessingStatusEnum.QUEUED,
                OrderProcessingStatusEnum.CANCELLED, REASON, NOW)).thenReturn(Mono.just(true));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectError(OrderNotFoundException.class)
                .verify();
    }

    @ParameterizedTest
    @MethodSource("inconsistentTicketOutcomes")
    void shouldReportInconsistentTicketsAsAConflict(InventoryWriteOutcome outcome) {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(reservedOrder(2)));
        when(inventoryTransactionPersistence.release(any())).thenReturn(Mono.just(outcome));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectError(InventoryConflictException.class)
                .verify();
    }

    static Stream<InventoryWriteOutcome> inconsistentTicketOutcomes() {
        return Stream.of(new InventoryWriteOutcome.InsufficientInventory(),
                new InventoryWriteOutcome.StaleTicketSelection());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ordersThatCannotBeCancelled")
    void shouldRejectOrdersThatCanNoLongerBeCancelled(String state, OrderData order) {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(order));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectError(InvalidOrderStateException.class)
                .verify();

        verifyNoInteractions(inventoryTransactionPersistence);
    }

    static Stream<Arguments> ordersThatCannotBeCancelled() {
        return Stream.of(
                Arguments.of("pending confirmation", pendingOrder(2)),
                Arguments.of("sold", soldOrder(2)),
                Arguments.of("expired", reservedOrder(2)
                        .withRelease(OrderProcessingStatusEnum.EXPIRED, "HOLD_EXPIRED", NOW)),
                Arguments.of("rejected", queuedOrder(2)
                        .withProcessingStatus(OrderProcessingStatusEnum.REJECTED, "SOLD_OUT", NOW)));
    }

    @Test
    void shouldHideAMissingOrder() {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(useCase().execute(REQUEST))
                .expectError(OrderNotFoundException.class)
                .verify();
    }

    @Test
    void shouldHideTheOrderOfAnotherCustomer() {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(reservedOrder(2)));

        StepVerifier.create(useCase().execute(new CancelOrderRequest(ORDER_ID, OTHER_CUSTOMER_ID)))
                .expectError(OrderNotFoundException.class)
                .verify();

        verifyNoInteractions(inventoryTransactionPersistence);
    }

    @Test
    void shouldBeLazy() {
        useCase().execute(REQUEST);

        verifyNoInteractions(orderPersistence, inventoryTransactionPersistence);
    }
}
