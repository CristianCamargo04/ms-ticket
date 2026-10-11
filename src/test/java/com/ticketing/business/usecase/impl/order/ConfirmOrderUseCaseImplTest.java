package com.ticketing.business.usecase.impl.order;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CLOCK;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CORRELATION_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CUSTOMER_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.NOW;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.ORDER_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.ORDER_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.OTHER_CUSTOMER_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.PUBLISH_RETRY_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.RETRY_HORIZON;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.pendingOrder;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.queuedOrder;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.reservedOrder;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.soldOrder;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.ticketIds;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.timedOutPendingOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.client.sdk.SqsOrderQueueClient;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.exception.InvalidOrderStateException;
import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.exception.MessagePublishingException;
import com.ticketing.domain.exception.OrderNotFoundException;
import com.ticketing.domain.exception.ReservationExpiredException;
import com.ticketing.domain.internal_request.ConfirmOrderRequest;
import com.ticketing.domain.internal_request.PaymentRequestedMessage;
import com.ticketing.domain.inventory.ConfirmInventoryCommand;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.persistence.InventoryTransactionPersistence;
import com.ticketing.persistence.OrderPersistence;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ConfirmOrderUseCaseImplTest {

    private static final String PAYMENT_TOKEN = "tok_visa_1";
    private static final ConfirmOrderRequest REQUEST =
            new ConfirmOrderRequest(ORDER_ID, CUSTOMER_ID, PAYMENT_TOKEN, CORRELATION_ID);

    @Mock
    private OrderPersistence orderPersistence;

    @Mock
    private InventoryTransactionPersistence inventoryTransactionPersistence;

    @Mock
    private SqsOrderQueueClient sqsOrderQueueClient;

    @Captor
    private ArgumentCaptor<ConfirmInventoryCommand> command;

    @Captor
    private ArgumentCaptor<PaymentRequestedMessage> message;

    private ConfirmOrderUseCaseImpl useCase() {
        return new ConfirmOrderUseCaseImpl(orderPersistence, inventoryTransactionPersistence, sqsOrderQueueClient,
                ORDER_POLICY, PUBLISH_RETRY_POLICY, CLOCK);
    }

    private void givenOrder(OrderData order) {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(order));
    }

    private static OrderData confirmed(OrderData reserved) {
        return reserved.withPendingConfirmation(ORDER_POLICY.confirmationDeadline(NOW), NOW);
    }

    @Test
    void shouldConfirmTheReservationAndRequestThePayment() {
        OrderData reserved = reservedOrder(2);
        givenOrder(reserved);
        when(inventoryTransactionPersistence.confirm(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.Applied(confirmed(reserved))));
        when(sqsOrderQueueClient.publishPaymentRequested(any())).thenReturn(Mono.just("msg-1"));

        StepVerifier.create(useCase().execute(REQUEST))
                .assertNext(order -> {
                    assertThat(order.ticketStatus()).isEqualTo(TicketStatusEnum.PENDING_CONFIRMATION);
                    assertThat(order.deadlineAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
                })
                .verifyComplete();

        verify(inventoryTransactionPersistence).confirm(command.capture());
        assertThat(command.getValue().order()).isEqualTo(reserved);
        assertThat(command.getValue().confirmationDeadline()).isEqualTo(ORDER_POLICY.confirmationDeadline(NOW));
        assertThat(command.getValue().now()).isEqualTo(NOW);
        assertThat(command.getValue().actor()).isEqualTo(CUSTOMER_ID);
        verify(sqsOrderQueueClient).publishPaymentRequested(message.capture());
        assertThat(message.getValue())
                .isEqualTo(new PaymentRequestedMessage(ORDER_ID, PAYMENT_TOKEN, CORRELATION_ID, NOW));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void shouldUseTheOrderIdAsCorrelationIdWhenNoneIsProvided(String correlationId) {
        OrderData reserved = reservedOrder(2);
        givenOrder(reserved);
        when(inventoryTransactionPersistence.confirm(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.Applied(confirmed(reserved))));
        when(sqsOrderQueueClient.publishPaymentRequested(any())).thenReturn(Mono.just("msg-1"));

        StepVerifier.create(useCase().execute(new ConfirmOrderRequest(ORDER_ID, CUSTOMER_ID, PAYMENT_TOKEN, correlationId)))
                .expectNextCount(1)
                .verifyComplete();

        verify(sqsOrderQueueClient).publishPaymentRequested(message.capture());
        assertThat(message.getValue().correlationId()).isEqualTo(ORDER_ID);
    }

    @Test
    void shouldKeepTheConfirmationWhenThePaymentMessageCannotBePublished() {
        OrderData reserved = reservedOrder(2);
        givenOrder(reserved);
        when(inventoryTransactionPersistence.confirm(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.Applied(confirmed(reserved))));
        when(sqsOrderQueueClient.publishPaymentRequested(any()))
                .thenReturn(Mono.error(new MessagePublishingException("queue down", new RuntimeException())));

        StepVerifier.withVirtualTime(() -> useCase().execute(REQUEST))
                .expectSubscription()
                .thenAwait(RETRY_HORIZON)
                .assertNext(order -> assertThat(order.ticketStatus()).isEqualTo(TicketStatusEnum.PENDING_CONFIRMATION))
                .verifyComplete();

        verify(sqsOrderQueueClient, times(PUBLISH_RETRY_POLICY.maxAttempts() + 1)).publishPaymentRequested(any());
    }

    @Test
    void shouldRetryATransientPaymentMessageFailure() {
        OrderData reserved = reservedOrder(2);
        givenOrder(reserved);
        when(inventoryTransactionPersistence.confirm(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.Applied(confirmed(reserved))));
        when(sqsOrderQueueClient.publishPaymentRequested(any()))
                .thenReturn(Mono.error(new MessagePublishingException("throttled", new RuntimeException())))
                .thenReturn(Mono.just("msg-1"));

        StepVerifier.withVirtualTime(() -> useCase().execute(REQUEST))
                .expectSubscription()
                .thenAwait(RETRY_HORIZON)
                .expectNextCount(1)
                .verifyComplete();

        verify(sqsOrderQueueClient, times(2)).publishPaymentRequested(any());
    }

    @Test
    void shouldRepublishThePaymentOfAnOrderAlreadyPendingConfirmation() {
        OrderData pending = pendingOrder(2);
        givenOrder(pending);
        when(sqsOrderQueueClient.publishPaymentRequested(any())).thenReturn(Mono.just("msg-1"));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(pending)
                .verifyComplete();

        verify(sqsOrderQueueClient).publishPaymentRequested(any());
        verifyNoInteractions(inventoryTransactionPersistence);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pendingOrdersPastTheirConfirmationDeadline")
    void shouldNotRepublishThePaymentOfAPendingOrderPastItsConfirmationDeadline(String state, OrderData pending) {
        givenOrder(pending);

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(pending)
                .verifyComplete();

        verifyNoInteractions(sqsOrderQueueClient, inventoryTransactionPersistence);
    }

    static Stream<Arguments> pendingOrdersPastTheirConfirmationDeadline() {
        return Stream.of(
                Arguments.of("deadline passed", timedOutPendingOrder(ORDER_ID, 2)),
                Arguments.of("deadline right now", pendingOrder(2).withDeadline(NOW, NOW.minusSeconds(1))));
    }

    @Test
    void shouldRepublishThePaymentOfAPendingOrderWithoutDeadline() {
        OrderData pending = pendingOrder(2).withDeadline(null, NOW.minusSeconds(1));
        givenOrder(pending);
        when(sqsOrderQueueClient.publishPaymentRequested(any())).thenReturn(Mono.just("msg-1"));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(pending)
                .verifyComplete();

        verify(sqsOrderQueueClient).publishPaymentRequested(any());
    }

    @Test
    void shouldReturnAPendingOrderEvenWhenItsPaymentCannotBeRepublished() {
        OrderData pending = pendingOrder(2);
        givenOrder(pending);
        when(sqsOrderQueueClient.publishPaymentRequested(any()))
                .thenReturn(Mono.error(new IllegalStateException("invalid queue")));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(pending)
                .verifyComplete();
    }

    @Test
    void shouldReturnASoldOrderAsIs() {
        OrderData sold = soldOrder(2);
        givenOrder(sold);

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(sold)
                .verifyComplete();

        verifyNoInteractions(inventoryTransactionPersistence, sqsOrderQueueClient);
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
        givenOrder(reservedOrder(2));

        StepVerifier.create(useCase().execute(new ConfirmOrderRequest(ORDER_ID, OTHER_CUSTOMER_ID, PAYMENT_TOKEN,
                        CORRELATION_ID)))
                .expectError(OrderNotFoundException.class)
                .verify();

        verifyNoInteractions(inventoryTransactionPersistence, sqsOrderQueueClient);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ordersThatCannotBeConfirmed")
    void shouldRejectOrdersThatAreNotReserved(String state, OrderData order) {
        givenOrder(order);

        StepVerifier.create(useCase().execute(REQUEST))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(InvalidOrderStateException.class)
                        .hasMessageContaining("cannot be confirmed"))
                .verify();

        verifyNoInteractions(inventoryTransactionPersistence, sqsOrderQueueClient);
    }

    static Stream<Arguments> ordersThatCannotBeConfirmed() {
        return Stream.of(
                Arguments.of("queued", queuedOrder(2)),
                Arguments.of("rejected", queuedOrder(2)
                        .withProcessingStatus(OrderProcessingStatusEnum.REJECTED, "SOLD_OUT", NOW)),
                Arguments.of("cancelled", reservedOrder(2)
                        .withRelease(OrderProcessingStatusEnum.CANCELLED, "CUSTOMER_CANCELLATION", NOW)),
                Arguments.of("complimentary", queuedOrder(OrderTypeEnum.COMPLIMENTARY, 2)
                        .withComplimentaryIssued(ticketIds(2), NOW)),
                Arguments.of("active without held tickets", reservedOrder(2)
                        .withState(TicketStatusEnum.AVAILABLE, OrderProcessingStatusEnum.ACTIVE, NOW)),
                Arguments.of("expired by confirmation timeout", pendingOrder(2)
                        .withRelease(OrderProcessingStatusEnum.EXPIRED, "CONFIRMATION_TIMEOUT", NOW)),
                Arguments.of("expired without reason", reservedOrder(2)
                        .withRelease(OrderProcessingStatusEnum.EXPIRED, null, NOW)),
                Arguments.of("payment declined", pendingOrder(2)
                        .withRelease(OrderProcessingStatusEnum.PAYMENT_DECLINED, "PAYMENT_DECLINED", NOW)));
    }

    @Test
    void shouldReportAnOrderReleasedByTheExpirationSweeperAsExpired() {
        givenOrder(reservedOrder(2).withRelease(OrderProcessingStatusEnum.EXPIRED, "HOLD_EXPIRED", NOW));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectError(ReservationExpiredException.class)
                .verify();

        verifyNoInteractions(inventoryTransactionPersistence);
    }

    @Test
    void shouldRejectAReservationWhoseHoldEndsNow() {
        givenOrder(reservedOrder(2, NOW));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectError(ReservationExpiredException.class)
                .verify();

        verifyNoInteractions(inventoryTransactionPersistence);
    }

    @Test
    void shouldReturnTheWinnerWhenAConcurrentConfirmationWon() {
        OrderData reserved = reservedOrder(2);
        OrderData winner = confirmed(reserved);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(reserved)).thenReturn(Mono.just(winner));
        when(inventoryTransactionPersistence.confirm(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.OrderStateChanged()));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(winner)
                .verifyComplete();

        verifyNoInteractions(sqsOrderQueueClient);
    }

    @Test
    void shouldReturnTheOrderWhenItWasSoldMeanwhile() {
        OrderData reserved = reservedOrder(2);
        OrderData sold = soldOrder(2);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(reserved)).thenReturn(Mono.just(sold));
        when(inventoryTransactionPersistence.confirm(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.OrderStateChanged()));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(sold)
                .verifyComplete();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expiredWinners")
    void shouldReportTheExpirationWhenTheSweeperWonTheRace(String state, OrderData current) {
        OrderData reserved = reservedOrder(2);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(reserved)).thenReturn(Mono.just(current));
        when(inventoryTransactionPersistence.confirm(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.OrderStateChanged()));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectError(ReservationExpiredException.class)
                .verify();
    }

    static Stream<Arguments> expiredWinners() {
        return Stream.of(
                Arguments.of("released by the sweeper", reservedOrder(2)
                        .withRelease(OrderProcessingStatusEnum.EXPIRED, "HOLD_EXPIRED", NOW)),
                Arguments.of("hold over", reservedOrder(2, NOW)));
    }

    @Test
    void shouldRejectTheConfirmationWhenTheOrderExpiredByConfirmationTimeoutMeanwhile() {
        OrderData reserved = reservedOrder(2);
        OrderData timedOut = pendingOrder(2)
                .withRelease(OrderProcessingStatusEnum.EXPIRED, "CONFIRMATION_TIMEOUT", NOW);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(reserved)).thenReturn(Mono.just(timedOut));
        when(inventoryTransactionPersistence.confirm(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.OrderStateChanged()));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectError(InvalidOrderStateException.class)
                .verify();
    }

    @Test
    void shouldRejectTheConfirmationWhenTheOrderWasCancelledMeanwhile() {
        OrderData reserved = reservedOrder(2);
        OrderData cancelled = reserved.withRelease(OrderProcessingStatusEnum.CANCELLED, "CUSTOMER_CANCELLATION", NOW);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(reserved)).thenReturn(Mono.just(cancelled));
        when(inventoryTransactionPersistence.confirm(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.OrderStateChanged()));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectError(InvalidOrderStateException.class)
                .verify();
    }

    @Test
    void shouldFailWhenTheOrderVanishedAfterALostRace() {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(reservedOrder(2))).thenReturn(Mono.empty());
        when(inventoryTransactionPersistence.confirm(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.OrderStateChanged()));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectError(OrderNotFoundException.class)
                .verify();
    }

    @ParameterizedTest
    @MethodSource("inconsistentTicketOutcomes")
    void shouldReportInconsistentTicketsAsAConflict(InventoryWriteOutcome outcome) {
        givenOrder(reservedOrder(2));
        when(inventoryTransactionPersistence.confirm(any())).thenReturn(Mono.just(outcome));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectError(InventoryConflictException.class)
                .verify();

        verifyNoInteractions(sqsOrderQueueClient);
    }

    static Stream<InventoryWriteOutcome> inconsistentTicketOutcomes() {
        return Stream.of(new InventoryWriteOutcome.InsufficientInventory(),
                new InventoryWriteOutcome.StaleTicketSelection());
    }

    @Test
    void shouldPropagateTransactionConflicts() {
        givenOrder(reservedOrder(2));
        when(inventoryTransactionPersistence.confirm(any()))
                .thenReturn(Mono.error(new InventoryConflictException("TransactionConflict")));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectErrorMessage("TransactionConflict")
                .verify();
    }

    @Test
    void shouldBeLazy() {
        useCase().execute(REQUEST);

        verifyNoInteractions(orderPersistence, inventoryTransactionPersistence, sqsOrderQueueClient);
    }
}
