package com.ticketing.business.usecase.impl.order;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CLOCK;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CORRELATION_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CUSTOMER_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.EVENT_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.NOW;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.ORDER_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.ORDER_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.PUBLISH_RETRY_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.REQUEST_HASH;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.RETRY_HORIZON;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.event;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.onSaleEvent;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.queuedOrder;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.reservedOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.business.usecase.impl.order.QueuedOrderAdmission.Admission;
import com.ticketing.cache.EventCacheService;
import com.ticketing.client.sdk.SqsOrderQueueClient;
import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.domain.exception.EventNotFoundException;
import com.ticketing.domain.exception.EventNotOnSaleException;
import com.ticketing.domain.exception.IdempotencyKeyConflictException;
import com.ticketing.domain.exception.InsufficientInventoryException;
import com.ticketing.domain.exception.MessagePublishingException;
import com.ticketing.domain.internal_request.OrderReservationMessage;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.persistence.EventPersistence;
import com.ticketing.persistence.OrderPersistence;
import java.util.function.Function;
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
class QueuedOrderAdmissionTest {

    private static final String IDEMPOTENCY_KEY = "key-12345678";

    @Mock
    private OrderPersistence orderPersistence;

    @Mock
    private EventPersistence eventPersistence;

    @Mock
    private EventCacheService eventCacheService;

    @Mock
    private SqsOrderQueueClient sqsOrderQueueClient;

    @Captor
    private ArgumentCaptor<OrderData> insertedOrder;

    @Captor
    private ArgumentCaptor<OrderReservationMessage> message;

    @Captor
    private ArgumentCaptor<Function<String, Mono<EventData>>> loader;

    private QueuedOrderAdmission admission() {
        return new QueuedOrderAdmission(orderPersistence, eventPersistence, eventCacheService, sqsOrderQueueClient,
                ORDER_POLICY, PUBLISH_RETRY_POLICY, CLOCK);
    }

    private static Admission request(boolean softAvailabilityCheck) {
        return new Admission(ORDER_ID, EVENT_ID, CUSTOMER_ID, OrderTypeEnum.STANDARD, 2, IDEMPOTENCY_KEY,
                REQUEST_HASH, CORRELATION_ID, softAvailabilityCheck);
    }

    private void givenNoExistingOrder() {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.empty());
    }

    private void givenCachedEvent(EventData event) {
        when(eventCacheService.get(eq(EVENT_ID), any())).thenReturn(Mono.just(event));
    }

    @Test
    void shouldQueueANewOrderAndPublishItsReservationMessage() {
        givenNoExistingOrder();
        givenCachedEvent(onSaleEvent(100, 50));
        when(orderPersistence.insertIfAbsent(any())).thenReturn(Mono.just(true));
        when(sqsOrderQueueClient.publishReservationRequested(any())).thenReturn(Mono.just("msg-1"));

        StepVerifier.create(admission().admit(request(true)))
                .assertNext(order -> {
                    assertThat(order.orderId()).isEqualTo(ORDER_ID);
                    assertThat(order.eventId()).isEqualTo(EVENT_ID);
                    assertThat(order.customerId()).isEqualTo(CUSTOMER_ID);
                    assertThat(order.type()).isEqualTo(OrderTypeEnum.STANDARD);
                    assertThat(order.quantity()).isEqualTo(2);
                    assertThat(order.processingStatus()).isEqualTo(OrderProcessingStatusEnum.QUEUED);
                    assertThat(order.ticketStatus()).isEqualTo(TicketStatusEnum.AVAILABLE);
                    assertThat(order.requestHash()).isEqualTo(REQUEST_HASH);
                    assertThat(order.deadlineAt()).isEqualTo(ORDER_POLICY.dispatchDeadline(NOW));
                    assertThat(order.createdAt()).isEqualTo(NOW);
                })
                .verifyComplete();

        verify(orderPersistence).insertIfAbsent(insertedOrder.capture());
        assertThat(insertedOrder.getValue().processingStatus()).isEqualTo(OrderProcessingStatusEnum.QUEUED);
        verify(sqsOrderQueueClient).publishReservationRequested(message.capture());
        assertThat(message.getValue())
                .isEqualTo(new OrderReservationMessage(ORDER_ID, EVENT_ID, CORRELATION_ID, NOW));
    }

    @Test
    void shouldLoadTheEventFromPersistenceThroughTheCache() {
        EventData event = onSaleEvent(100, 50);
        givenNoExistingOrder();
        givenCachedEvent(event);
        when(orderPersistence.insertIfAbsent(any())).thenReturn(Mono.just(true));
        when(sqsOrderQueueClient.publishReservationRequested(any())).thenReturn(Mono.just("msg-1"));
        when(eventPersistence.findById(EVENT_ID)).thenReturn(Mono.just(event));

        StepVerifier.create(admission().admit(request(true))).expectNextCount(1).verifyComplete();

        verify(eventCacheService).get(eq(EVENT_ID), loader.capture());
        StepVerifier.create(loader.getValue().apply(EVENT_ID)).expectNext(event).verifyComplete();
    }

    @Test
    void shouldReplayAQueuedOrderWithTheSameRequestAndRepublishIt() {
        OrderData existing = queuedOrder(2);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(existing));
        when(sqsOrderQueueClient.publishReservationRequested(any())).thenReturn(Mono.just("msg-2"));

        StepVerifier.create(admission().admit(request(true)))
                .expectNext(existing)
                .verifyComplete();

        verify(sqsOrderQueueClient).publishReservationRequested(any());
        verify(orderPersistence, never()).insertIfAbsent(any());
        verifyNoInteractions(eventCacheService);
    }

    @Test
    void shouldReplayAProcessedOrderWithoutRepublishingIt() {
        OrderData existing = reservedOrder(2);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(existing));

        StepVerifier.create(admission().admit(request(true)))
                .expectNext(existing)
                .verifyComplete();

        verifyNoInteractions(sqsOrderQueueClient, eventCacheService);
    }

    @Test
    void shouldRejectTheReuseOfAnIdempotencyKeyForAnotherRequest() {
        Admission otherPayload = new Admission(ORDER_ID, EVENT_ID, CUSTOMER_ID, OrderTypeEnum.STANDARD, 3,
                IDEMPOTENCY_KEY, "another-hash", CORRELATION_ID, true);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(queuedOrder(2)));

        StepVerifier.create(admission().admit(otherPayload))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IdempotencyKeyConflictException.class)
                        .hasMessageContaining(IDEMPOTENCY_KEY))
                .verify();

        verifyNoInteractions(sqsOrderQueueClient, eventCacheService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sameFingerprintForAnotherOrder")
    void shouldRejectTheReuseOfAnIdempotencyKeyForAnotherOrderEvenWithTheSameFingerprint(String difference,
                                                                                         Admission otherOrder) {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(queuedOrder(2)));

        StepVerifier.create(admission().admit(otherOrder))
                .expectError(IdempotencyKeyConflictException.class)
                .verify();

        verifyNoInteractions(sqsOrderQueueClient, eventCacheService);
    }

    static Stream<Arguments> sameFingerprintForAnotherOrder() {
        return Stream.of(
                Arguments.of("another beneficiary", new Admission(ORDER_ID, EVENT_ID, "beneficiary-2",
                        OrderTypeEnum.STANDARD, 2, IDEMPOTENCY_KEY, REQUEST_HASH, CORRELATION_ID, true)),
                Arguments.of("another event", new Admission(ORDER_ID, "other-event", CUSTOMER_ID,
                        OrderTypeEnum.STANDARD, 2, IDEMPOTENCY_KEY, REQUEST_HASH, CORRELATION_ID, true)),
                Arguments.of("another type", new Admission(ORDER_ID, EVENT_ID, CUSTOMER_ID,
                        OrderTypeEnum.COMPLIMENTARY, 2, IDEMPOTENCY_KEY, REQUEST_HASH, CORRELATION_ID, false)),
                Arguments.of("another quantity", new Admission(ORDER_ID, EVENT_ID, CUSTOMER_ID,
                        OrderTypeEnum.STANDARD, 3, IDEMPOTENCY_KEY, REQUEST_HASH, CORRELATION_ID, true)));
    }

    @Test
    void shouldRejectAMissingEvent() {
        givenNoExistingOrder();
        when(eventCacheService.get(eq(EVENT_ID), any())).thenReturn(Mono.empty());

        StepVerifier.create(admission().admit(request(true)))
                .expectError(EventNotFoundException.class)
                .verify();

        verify(orderPersistence, never()).insertIfAbsent(any());
    }

    @Test
    void shouldRejectAnEventThatIsNotOnSale() {
        givenNoExistingOrder();
        givenCachedEvent(event(EventStatusEnum.CLOSED, 100, 100));

        StepVerifier.create(admission().admit(request(false)))
                .expectError(EventNotOnSaleException.class)
                .verify();

        verify(orderPersistence, never()).insertIfAbsent(any());
    }

    @Test
    void shouldRejectASoldOutEventWhenTheSoftAvailabilityCheckApplies() {
        givenNoExistingOrder();
        givenCachedEvent(onSaleEvent(100, 0));

        StepVerifier.create(admission().admit(request(true)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(InsufficientInventoryException.class)
                        .hasMessageContaining("requested 2, available 0"))
                .verify();

        verify(orderPersistence, never()).insertIfAbsent(any());
    }

    @Test
    void shouldQueueTheOrderOfASoldOutEventWithoutTheSoftAvailabilityCheck() {
        givenNoExistingOrder();
        givenCachedEvent(onSaleEvent(100, 0));
        when(orderPersistence.insertIfAbsent(any())).thenReturn(Mono.just(true));
        when(sqsOrderQueueClient.publishReservationRequested(any())).thenReturn(Mono.just("msg-1"));

        StepVerifier.create(admission().admit(request(false)))
                .assertNext(order -> assertThat(order.processingStatus()).isEqualTo(OrderProcessingStatusEnum.QUEUED))
                .verifyComplete();
    }

    @Test
    void shouldQueueTheOrderWhenSomeTicketsAreLeftEvenIfFewerThanRequested() {
        givenNoExistingOrder();
        givenCachedEvent(onSaleEvent(100, 1));
        when(orderPersistence.insertIfAbsent(any())).thenReturn(Mono.just(true));
        when(sqsOrderQueueClient.publishReservationRequested(any())).thenReturn(Mono.just("msg-1"));

        StepVerifier.create(admission().admit(request(true)))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void shouldReplayTheWinnerOfAConcurrentDuplicateRequest() {
        OrderData winner = queuedOrder(2);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.empty()).thenReturn(Mono.just(winner));
        givenCachedEvent(onSaleEvent(100, 50));
        when(orderPersistence.insertIfAbsent(any())).thenReturn(Mono.just(false));
        when(sqsOrderQueueClient.publishReservationRequested(any())).thenReturn(Mono.just("msg-1"));

        StepVerifier.create(admission().admit(request(true)))
                .expectNext(winner)
                .verifyComplete();

        verify(orderPersistence, times(2)).findById(ORDER_ID);
        verify(sqsOrderQueueClient).publishReservationRequested(any());
    }

    @Test
    void shouldRejectAConcurrentDuplicateWithAnotherPayload() {
        OrderData winner = OrderData.newQueued(ORDER_ID, EVENT_ID, CUSTOMER_ID, OrderTypeEnum.STANDARD, 5,
                "winner-hash", NOW, NOW);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.empty()).thenReturn(Mono.just(winner));
        givenCachedEvent(onSaleEvent(100, 50));
        when(orderPersistence.insertIfAbsent(any())).thenReturn(Mono.just(false));

        StepVerifier.create(admission().admit(request(true)))
                .expectError(IdempotencyKeyConflictException.class)
                .verify();

        verifyNoInteractions(sqsOrderQueueClient);
    }

    @Test
    void shouldFailWhenTheConcurrentDuplicateCannotBeRead() {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.empty()).thenReturn(Mono.empty());
        givenCachedEvent(onSaleEvent(100, 50));
        when(orderPersistence.insertIfAbsent(any())).thenReturn(Mono.just(false));

        StepVerifier.create(admission().admit(request(true)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(ORDER_ID))
                .verify();
    }

    @Test
    void shouldRetryATransientPublicationFailure() {
        givenNoExistingOrder();
        givenCachedEvent(onSaleEvent(100, 50));
        when(orderPersistence.insertIfAbsent(any())).thenReturn(Mono.just(true));
        when(sqsOrderQueueClient.publishReservationRequested(any()))
                .thenReturn(Mono.error(new MessagePublishingException("throttled", new RuntimeException())))
                .thenReturn(Mono.just("msg-1"));

        StepVerifier.withVirtualTime(() -> admission().admit(request(true)))
                .expectSubscription()
                .thenAwait(RETRY_HORIZON)
                .assertNext(order -> assertThat(order.orderId()).isEqualTo(ORDER_ID))
                .verifyComplete();

        verify(sqsOrderQueueClient, times(2)).publishReservationRequested(any());
    }

    @Test
    void shouldStillAcceptThePersistedOrderWhenThePublicationKeepsFailing() {
        givenNoExistingOrder();
        givenCachedEvent(onSaleEvent(100, 50));
        when(orderPersistence.insertIfAbsent(any())).thenReturn(Mono.just(true));
        when(sqsOrderQueueClient.publishReservationRequested(any()))
                .thenReturn(Mono.error(new MessagePublishingException("queue down", new RuntimeException())));

        StepVerifier.withVirtualTime(() -> admission().admit(request(true)))
                .expectSubscription()
                .thenAwait(RETRY_HORIZON)
                .assertNext(order -> assertThat(order.processingStatus()).isEqualTo(OrderProcessingStatusEnum.QUEUED))
                .verifyComplete();

        verify(sqsOrderQueueClient, times(PUBLISH_RETRY_POLICY.maxAttempts() + 1)).publishReservationRequested(any());
    }

    @Test
    void shouldNotRetryANonRetryablePublicationFailure() {
        givenNoExistingOrder();
        givenCachedEvent(onSaleEvent(100, 50));
        when(orderPersistence.insertIfAbsent(any())).thenReturn(Mono.just(true));
        when(sqsOrderQueueClient.publishReservationRequested(any()))
                .thenReturn(Mono.error(new IllegalArgumentException("message too large")));

        StepVerifier.create(admission().admit(request(true)))
                .expectNextCount(1)
                .verifyComplete();

        verify(sqsOrderQueueClient, times(1)).publishReservationRequested(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void shouldUseTheOrderIdAsCorrelationIdWhenNoneIsProvided(String correlationId) {
        Admission withoutCorrelation = new Admission(ORDER_ID, EVENT_ID, CUSTOMER_ID, OrderTypeEnum.STANDARD, 2,
                IDEMPOTENCY_KEY, REQUEST_HASH, correlationId, true);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(queuedOrder(2)));
        when(sqsOrderQueueClient.publishReservationRequested(any())).thenReturn(Mono.just("msg-1"));

        StepVerifier.create(admission().admit(withoutCorrelation)).expectNextCount(1).verifyComplete();

        verify(sqsOrderQueueClient).publishReservationRequested(message.capture());
        assertThat(message.getValue().correlationId()).isEqualTo(ORDER_ID);
    }

    @Test
    void shouldBeLazy() {
        admission().admit(request(true));

        verifyNoInteractions(orderPersistence, eventCacheService, sqsOrderQueueClient);
    }

    @Test
    void shouldValidateTheQuantity() {
        assertThatCode(() -> QueuedOrderAdmission.requireQuantity(1, 10)).doesNotThrowAnyException();
        assertThatCode(() -> QueuedOrderAdmission.requireQuantity(10, 10)).doesNotThrowAnyException();
        assertThatThrownBy(() -> QueuedOrderAdmission.requireQuantity(0, 10))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("quantity must be between 1 and 10");
        assertThatThrownBy(() -> QueuedOrderAdmission.requireQuantity(11, 10))
                .isInstanceOf(DomainValidationException.class);
    }
}
