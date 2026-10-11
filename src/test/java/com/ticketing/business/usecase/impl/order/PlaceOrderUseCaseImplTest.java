package com.ticketing.business.usecase.impl.order;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CLOCK;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CORRELATION_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CUSTOMER_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.EVENT_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.ORDER_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.PUBLISH_RETRY_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.REQUEST_HASH;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.onSaleEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.cache.EventCacheService;
import com.ticketing.client.sdk.SqsOrderQueueClient;
import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.domain.exception.InsufficientInventoryException;
import com.ticketing.domain.internal_request.OrderReservationMessage;
import com.ticketing.domain.internal_request.PlaceOrderRequest;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.persistence.EventPersistence;
import com.ticketing.persistence.OrderPersistence;
import com.ticketing.util.DeterministicIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class PlaceOrderUseCaseImplTest {

    private static final String IDEMPOTENCY_KEY = "key-12345678";
    private static final String EXPECTED_ORDER_ID =
            DeterministicIdUtil.nameBasedId(CUSTOMER_ID + ":" + IDEMPOTENCY_KEY);

    @Mock
    private OrderPersistence orderPersistence;

    @Mock
    private EventPersistence eventPersistence;

    @Mock
    private EventCacheService eventCacheService;

    @Mock
    private SqsOrderQueueClient sqsOrderQueueClient;

    @Captor
    private ArgumentCaptor<OrderReservationMessage> message;

    private PlaceOrderUseCaseImpl useCase() {
        return new PlaceOrderUseCaseImpl(orderPersistence, eventPersistence, eventCacheService, sqsOrderQueueClient,
                ORDER_POLICY, PUBLISH_RETRY_POLICY, CLOCK);
    }

    private static PlaceOrderRequest request(int quantity) {
        return new PlaceOrderRequest(EVENT_ID, CUSTOMER_ID, quantity, IDEMPOTENCY_KEY, REQUEST_HASH, CORRELATION_ID);
    }

    @Test
    void shouldQueueAStandardOrderWithADeterministicIdAndPublishIt() {
        when(orderPersistence.findById(EXPECTED_ORDER_ID)).thenReturn(Mono.empty());
        when(eventCacheService.get(eq(EVENT_ID), any())).thenReturn(Mono.just(onSaleEvent(100, 50)));
        when(orderPersistence.insertIfAbsent(any())).thenReturn(Mono.just(true));
        when(sqsOrderQueueClient.publishReservationRequested(any())).thenReturn(Mono.just("msg-1"));

        StepVerifier.create(useCase().execute(request(3)))
                .assertNext(order -> {
                    assertThat(order.orderId()).isEqualTo(EXPECTED_ORDER_ID);
                    assertThat(order.customerId()).isEqualTo(CUSTOMER_ID);
                    assertThat(order.type()).isEqualTo(OrderTypeEnum.STANDARD);
                    assertThat(order.quantity()).isEqualTo(3);
                    assertThat(order.processingStatus()).isEqualTo(OrderProcessingStatusEnum.QUEUED);
                })
                .verifyComplete();

        verify(sqsOrderQueueClient).publishReservationRequested(message.capture());
        assertThat(message.getValue().orderId()).isEqualTo(EXPECTED_ORDER_ID);
        assertThat(message.getValue().correlationId()).isEqualTo(CORRELATION_ID);
    }

    @Test
    void shouldApplyTheSoftAvailabilityCheck() {
        when(orderPersistence.findById(EXPECTED_ORDER_ID)).thenReturn(Mono.empty());
        when(eventCacheService.get(eq(EVENT_ID), any())).thenReturn(Mono.just(onSaleEvent(100, 0)));

        StepVerifier.create(useCase().execute(request(1)))
                .expectError(InsufficientInventoryException.class)
                .verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 11})
    void shouldRejectAQuantityOutsideOfTheOrderLimit(int quantity) {
        StepVerifier.create(useCase().execute(request(quantity)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(DomainValidationException.class)
                        .hasMessage("quantity must be between 1 and " + ORDER_POLICY.maxTicketsPerOrder()))
                .verify();

        verifyNoInteractions(orderPersistence, eventCacheService, sqsOrderQueueClient);
    }

    @Test
    void shouldBeLazy() {
        useCase().execute(request(0));
        useCase().execute(request(1));

        verifyNoInteractions(orderPersistence, eventCacheService, sqsOrderQueueClient);
    }
}
