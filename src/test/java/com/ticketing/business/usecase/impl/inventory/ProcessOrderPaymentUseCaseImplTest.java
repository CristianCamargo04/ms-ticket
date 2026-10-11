package com.ticketing.business.usecase.impl.inventory;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CLOCK;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CORRELATION_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CURRENCY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CUSTOMER_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.EVENT_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.INVENTORY_RETRY_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.NOW;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.ORDER_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.RETRY_HORIZON;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.onSaleEvent;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.pendingOrder;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.reservedOrder;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.soldOrder;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.timedOutPendingOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ticketing.client.rest.api.PaymentGatewayChargeApi;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.exception.EventNotFoundException;
import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.exception.OrderNotFoundException;
import com.ticketing.domain.exception.PaymentGatewayException;
import com.ticketing.domain.external_request.payment.PaymentChargeRequest;
import com.ticketing.domain.internal_request.ProcessOrderPaymentRequest;
import com.ticketing.domain.inventory.FinalizeSaleCommand;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import com.ticketing.domain.inventory.ReleaseInventoryCommand;
import com.ticketing.domain.order.PaymentOutcome;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import com.ticketing.persistence.EventPersistence;
import com.ticketing.persistence.InventoryTransactionPersistence;
import com.ticketing.persistence.OrderPersistence;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ProcessOrderPaymentUseCaseImplTest {

    private static final String PAYMENT_TOKEN = "tok_visa_1";
    private static final ProcessOrderPaymentRequest REQUEST =
            new ProcessOrderPaymentRequest(ORDER_ID, PAYMENT_TOKEN, CORRELATION_ID);

    @Mock
    private OrderPersistence orderPersistence;

    @Mock
    private EventPersistence eventPersistence;

    @Mock
    private InventoryTransactionPersistence inventoryTransactionPersistence;

    @Mock
    private PaymentGatewayChargeApi paymentGatewayChargeApi;

    @Captor
    private ArgumentCaptor<PaymentChargeRequest> charge;

    @Captor
    private ArgumentCaptor<FinalizeSaleCommand> finalizeCommand;

    @Captor
    private ArgumentCaptor<ReleaseInventoryCommand> releaseCommand;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ProcessOrderPaymentUseCaseImpl.class);

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private ProcessOrderPaymentUseCaseImpl useCase() {
        return new ProcessOrderPaymentUseCaseImpl(orderPersistence, eventPersistence, inventoryTransactionPersistence,
                paymentGatewayChargeApi, INVENTORY_RETRY_POLICY, CLOCK);
    }

    private void givenPendingOrderOfAnEvent(OrderData order) {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(order));
        when(eventPersistence.findById(EVENT_ID)).thenReturn(Mono.just(onSaleEvent(100, 50)));
    }

    private boolean refundAlertLogged() {
        return logs.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                && event.getFormattedMessage().contains("Payment captured for released order " + ORDER_ID));
    }

    @Test
    void shouldFinalizeTheSaleWhenThePaymentIsApproved() {
        OrderData pending = pendingOrder(2);
        givenPendingOrderOfAnEvent(pending);
        when(paymentGatewayChargeApi.charge(any())).thenReturn(Mono.just(new PaymentOutcome.Approved("pay-1")));
        when(inventoryTransactionPersistence.finalizeSale(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.Applied(pending.withSale("pay-1", NOW))));

        StepVerifier.create(useCase().execute(REQUEST))
                .assertNext(order -> {
                    assertThat(order.ticketStatus()).isEqualTo(TicketStatusEnum.SOLD);
                    assertThat(order.processingStatus()).isEqualTo(OrderProcessingStatusEnum.COMPLETED);
                    assertThat(order.paymentReference()).isEqualTo("pay-1");
                })
                .verifyComplete();

        verify(paymentGatewayChargeApi).charge(charge.capture());
        assertThat(charge.getValue()).isEqualTo(new PaymentChargeRequest(ORDER_ID, ORDER_ID, CUSTOMER_ID,
                new BigDecimal("51.00"), CURRENCY, PAYMENT_TOKEN));
        verify(inventoryTransactionPersistence).finalizeSale(finalizeCommand.capture());
        assertThat(finalizeCommand.getValue().order()).isEqualTo(pending);
        assertThat(finalizeCommand.getValue().paymentReference()).isEqualTo("pay-1");
        assertThat(finalizeCommand.getValue().now()).isEqualTo(NOW);
        assertThat(finalizeCommand.getValue().actor()).isEqualTo(ProcessOrderPaymentUseCaseImpl.ACTOR);
        assertThat(refundAlertLogged()).isFalse();
    }

    @Test
    void shouldReleaseTheTicketsWhenThePaymentIsDeclined() {
        OrderData pending = pendingOrder(2);
        givenPendingOrderOfAnEvent(pending);
        when(paymentGatewayChargeApi.charge(any())).thenReturn(Mono.just(new PaymentOutcome.Declined("insufficient")));
        when(inventoryTransactionPersistence.release(any())).thenReturn(Mono.just(new InventoryWriteOutcome.Applied(
                pending.withRelease(OrderProcessingStatusEnum.PAYMENT_DECLINED, "PAYMENT_DECLINED", NOW))));

        StepVerifier.create(useCase().execute(REQUEST))
                .assertNext(order -> {
                    assertThat(order.ticketStatus()).isEqualTo(TicketStatusEnum.AVAILABLE);
                    assertThat(order.processingStatus()).isEqualTo(OrderProcessingStatusEnum.PAYMENT_DECLINED);
                })
                .verifyComplete();

        verify(inventoryTransactionPersistence).release(releaseCommand.capture());
        ReleaseInventoryCommand command = releaseCommand.getValue();
        assertThat(command.expectedStatus()).isEqualTo(TicketStatusEnum.PENDING_CONFIRMATION);
        assertThat(command.resultingStatus()).isEqualTo(OrderProcessingStatusEnum.PAYMENT_DECLINED);
        assertThat(command.reason()).isEqualTo(TransitionReasonEnum.PAYMENT_DECLINED);
        assertThat(command.actor()).isEqualTo(ProcessOrderPaymentUseCaseImpl.ACTOR);
    }

    @Test
    void shouldRetryATransientConflictOfTheSettlementWithoutChargingAgain() {
        OrderData pending = pendingOrder(2);
        givenPendingOrderOfAnEvent(pending);
        when(paymentGatewayChargeApi.charge(any())).thenReturn(Mono.just(new PaymentOutcome.Approved("pay-1")));
        when(inventoryTransactionPersistence.finalizeSale(any()))
                .thenReturn(Mono.error(new InventoryConflictException("TransactionConflict")))
                .thenReturn(Mono.just(new InventoryWriteOutcome.Applied(pending.withSale("pay-1", NOW))));

        StepVerifier.withVirtualTime(() -> useCase().execute(REQUEST))
                .expectSubscription()
                .thenAwait(RETRY_HORIZON)
                .assertNext(order -> assertThat(order.ticketStatus()).isEqualTo(TicketStatusEnum.SOLD))
                .verifyComplete();

        verify(inventoryTransactionPersistence, times(2)).finalizeSale(any());
        verify(paymentGatewayChargeApi, times(1)).charge(any());
    }

    @Test
    void shouldPropagateTheConflictOnceTheSettlementRetriesAreExhausted() {
        givenPendingOrderOfAnEvent(pendingOrder(2));
        when(paymentGatewayChargeApi.charge(any())).thenReturn(Mono.just(new PaymentOutcome.Declined(null)));
        when(inventoryTransactionPersistence.release(any()))
                .thenReturn(Mono.error(new InventoryConflictException("TransactionConflict")));

        StepVerifier.withVirtualTime(() -> useCase().execute(REQUEST))
                .expectSubscription()
                .thenAwait(RETRY_HORIZON)
                .expectError(InventoryConflictException.class)
                .verify();

        verify(inventoryTransactionPersistence, times(INVENTORY_RETRY_POLICY.maxAttempts() + 1)).release(any());
    }

    @Test
    void shouldReturnTheSoldOrderWhenAnotherDeliveryFinalizedItMeanwhile() {
        OrderData pending = pendingOrder(2);
        OrderData sold = soldOrder(2);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(pending)).thenReturn(Mono.just(sold));
        when(eventPersistence.findById(EVENT_ID)).thenReturn(Mono.just(onSaleEvent(100, 50)));
        when(paymentGatewayChargeApi.charge(any())).thenReturn(Mono.just(new PaymentOutcome.Approved("pay-1")));
        when(inventoryTransactionPersistence.finalizeSale(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.OrderStateChanged()));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(sold)
                .verifyComplete();

        assertThat(refundAlertLogged()).isFalse();
    }

    @Test
    void shouldRaiseARefundAlertWhenAnApprovedPaymentMeetsAReleasedOrder() {
        OrderData pending = pendingOrder(2);
        OrderData released = pending.withRelease(OrderProcessingStatusEnum.EXPIRED, "CONFIRMATION_TIMEOUT", NOW);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(pending)).thenReturn(Mono.just(released));
        when(eventPersistence.findById(EVENT_ID)).thenReturn(Mono.just(onSaleEvent(100, 50)));
        when(paymentGatewayChargeApi.charge(any())).thenReturn(Mono.just(new PaymentOutcome.Approved("pay-1")));
        when(inventoryTransactionPersistence.finalizeSale(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.OrderStateChanged()));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(released)
                .verifyComplete();

        assertThat(refundAlertLogged()).isTrue();
    }

    @Test
    void shouldReturnTheCurrentOrderWhenADeclinedPaymentMeetsAReleasedOrder() {
        OrderData pending = pendingOrder(2);
        OrderData released = pending.withRelease(OrderProcessingStatusEnum.EXPIRED, "CONFIRMATION_TIMEOUT", NOW);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(pending)).thenReturn(Mono.just(released));
        when(eventPersistence.findById(EVENT_ID)).thenReturn(Mono.just(onSaleEvent(100, 50)));
        when(paymentGatewayChargeApi.charge(any())).thenReturn(Mono.just(new PaymentOutcome.Declined("expired card")));
        when(inventoryTransactionPersistence.release(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.OrderStateChanged()));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(released)
                .verifyComplete();

        assertThat(refundAlertLogged()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("inconsistentTicketOutcomes")
    void shouldReportInconsistentTicketsAsAConflict(InventoryWriteOutcome outcome) {
        givenPendingOrderOfAnEvent(pendingOrder(2));
        when(paymentGatewayChargeApi.charge(any())).thenReturn(Mono.just(new PaymentOutcome.Approved("pay-1")));
        when(inventoryTransactionPersistence.finalizeSale(any())).thenReturn(Mono.just(outcome));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectError(InventoryConflictException.class)
                .verify();
    }

    static Stream<InventoryWriteOutcome> inconsistentTicketOutcomes() {
        return Stream.of(new InventoryWriteOutcome.InsufficientInventory(),
                new InventoryWriteOutcome.StaleTicketSelection());
    }

    @Test
    void shouldTreatAMissingChargeResultAsAGatewayFailure() {
        givenPendingOrderOfAnEvent(pendingOrder(2));
        when(paymentGatewayChargeApi.charge(any())).thenReturn(Mono.just(new PaymentOutcome.NotFound()));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectError(PaymentGatewayException.class)
                .verify();

        verifyNoInteractions(inventoryTransactionPersistence);
    }

    @Test
    void shouldPropagateGatewayFailuresSoThatTheMessageIsRedelivered() {
        givenPendingOrderOfAnEvent(pendingOrder(2));
        when(paymentGatewayChargeApi.charge(any()))
                .thenReturn(Mono.error(new PaymentGatewayException("circuit open")));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectErrorMessage("circuit open")
                .verify();

        verifyNoInteractions(inventoryTransactionPersistence);
    }

    @Test
    void shouldFailWhenTheEventDoesNotExist() {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(pendingOrder(2)));
        when(eventPersistence.findById(EVENT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(useCase().execute(REQUEST))
                .expectError(EventNotFoundException.class)
                .verify();

        verifyNoInteractions(paymentGatewayChargeApi, inventoryTransactionPersistence);
    }

    @Test
    void shouldIgnoreOrdersThatAreNotPendingConfirmation() {
        OrderData reserved = reservedOrder(2);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(reserved));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(reserved)
                .verifyComplete();

        verifyNoInteractions(eventPersistence, paymentGatewayChargeApi, inventoryTransactionPersistence);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ordersPastTheirConfirmationDeadline")
    void shouldNotChargeAnOrderWhoseConfirmationDeadlineIsDue(String state, OrderData order) {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(order));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(order)
                .verifyComplete();

        verifyNoInteractions(eventPersistence, paymentGatewayChargeApi, inventoryTransactionPersistence);
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(ORDER_ID, "confirmation deadline");
        });
    }

    static Stream<Arguments> ordersPastTheirConfirmationDeadline() {
        return Stream.of(
                Arguments.of("deadline passed", timedOutPendingOrder(ORDER_ID, 2)),
                Arguments.of("deadline right now", pendingOrder(2).withDeadline(NOW, NOW.minusSeconds(1))));
    }

    @Test
    void shouldChargeAnOrderPendingConfirmationOneMillisecondBeforeItsDeadline() {
        OrderData pending = pendingOrder(2).withDeadline(NOW.plusMillis(1), NOW.minusSeconds(1));
        givenPendingOrderOfAnEvent(pending);
        when(paymentGatewayChargeApi.charge(any())).thenReturn(Mono.just(new PaymentOutcome.Approved("pay-1")));
        when(inventoryTransactionPersistence.finalizeSale(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.Applied(pending.withSale("pay-1", NOW))));

        StepVerifier.create(useCase().execute(REQUEST))
                .assertNext(order -> assertThat(order.ticketStatus()).isEqualTo(TicketStatusEnum.SOLD))
                .verifyComplete();

        verify(paymentGatewayChargeApi).charge(any());
    }

    @Test
    void shouldChargeAPendingOrderWithoutDeadline() {
        OrderData pending = pendingOrder(2).withDeadline(null, NOW.minusSeconds(1));
        givenPendingOrderOfAnEvent(pending);
        when(paymentGatewayChargeApi.charge(any())).thenReturn(Mono.just(new PaymentOutcome.Declined("expired")));
        when(inventoryTransactionPersistence.release(any())).thenReturn(Mono.just(new InventoryWriteOutcome.Applied(
                pending.withRelease(OrderProcessingStatusEnum.PAYMENT_DECLINED, "PAYMENT_DECLINED", NOW))));

        StepVerifier.create(useCase().execute(REQUEST))
                .assertNext(order -> assertThat(order.processingStatus())
                        .isEqualTo(OrderProcessingStatusEnum.PAYMENT_DECLINED))
                .verifyComplete();

        verify(paymentGatewayChargeApi).charge(any());
    }

    @Test
    void shouldFailWhenTheOrderDoesNotExist() {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(useCase().execute(REQUEST))
                .expectError(OrderNotFoundException.class)
                .verify();
    }

    @Test
    void shouldBeLazy() {
        useCase().execute(REQUEST);

        verifyNoInteractions(orderPersistence, eventPersistence, paymentGatewayChargeApi,
                inventoryTransactionPersistence);
    }
}
