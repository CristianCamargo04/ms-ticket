package com.ticketing.business.usecase.impl.order;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CLOCK;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CORRELATION_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.EVENT_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.NOW;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.ORDER_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.PUBLISH_RETRY_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.REQUEST_HASH;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.event;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.onSaleEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.cache.EventCacheService;
import com.ticketing.client.sdk.SqsOrderQueueClient;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.domain.exception.EventNotOnSaleException;
import com.ticketing.domain.exception.IdempotencyKeyConflictException;
import com.ticketing.domain.internal_request.IssueComplimentaryRequest;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.persistence.EventPersistence;
import com.ticketing.persistence.OrderPersistence;
import com.ticketing.util.DeterministicIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class IssueComplimentaryTicketsUseCaseImplTest {
    private static final String ADMIN_ID = "admin-1";
    private static final String BENEFICIARY_ID = "vip-1";
    private static final String IDEMPOTENCY_KEY = "key-12345678";
    private static final String EXPECTED_ORDER_ID =
            DeterministicIdUtil.nameBasedId(ADMIN_ID + ":" + IDEMPOTENCY_KEY);

    @Mock
    private OrderPersistence orderPersistence;

    @Mock
    private EventPersistence eventPersistence;

    @Mock
    private EventCacheService eventCacheService;

    @Mock
    private SqsOrderQueueClient sqsOrderQueueClient;

    private IssueComplimentaryTicketsUseCaseImpl useCase() {
        return new IssueComplimentaryTicketsUseCaseImpl(orderPersistence, eventPersistence, eventCacheService,
                sqsOrderQueueClient, ORDER_POLICY, PUBLISH_RETRY_POLICY, CLOCK);
    }

    private static IssueComplimentaryRequest request(int quantity) {
        return new IssueComplimentaryRequest(EVENT_ID, BENEFICIARY_ID, quantity, ADMIN_ID, IDEMPOTENCY_KEY,
                REQUEST_HASH, CORRELATION_ID);
    }

    @Test
    void shouldQueueAComplimentaryOrderOwnedByTheBeneficiaryWithoutTheSoftAvailabilityCheck() {
        when(orderPersistence.findById(EXPECTED_ORDER_ID)).thenReturn(Mono.empty());
        when(eventCacheService.get(eq(EVENT_ID), any())).thenReturn(Mono.just(onSaleEvent(100, 0)));
        when(orderPersistence.insertIfAbsent(any())).thenReturn(Mono.just(true));
        when(sqsOrderQueueClient.publishReservationRequested(any())).thenReturn(Mono.just("msg-1"));

        StepVerifier.create(useCase().execute(request(ORDER_POLICY.maxComplimentaryPerRequest())))
                .assertNext(order -> {
                    assertThat(order.orderId()).isEqualTo(EXPECTED_ORDER_ID);
                    assertThat(order.customerId()).isEqualTo(BENEFICIARY_ID);
                    assertThat(order.type()).isEqualTo(OrderTypeEnum.COMPLIMENTARY);
                    assertThat(order.quantity()).isEqualTo(ORDER_POLICY.maxComplimentaryPerRequest());
                    assertThat(order.processingStatus()).isEqualTo(OrderProcessingStatusEnum.QUEUED);
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectAKeyReusedForAnotherBeneficiaryEvenWithTheSameFingerprint() {
        OrderData firstIssue = OrderData.newQueued(EXPECTED_ORDER_ID, EVENT_ID, BENEFICIARY_ID,
                OrderTypeEnum.COMPLIMENTARY, 2, REQUEST_HASH, NOW, NOW);
        when(orderPersistence.findById(EXPECTED_ORDER_ID)).thenReturn(Mono.just(firstIssue));

        StepVerifier.create(useCase().execute(new IssueComplimentaryRequest(EVENT_ID, "vip-2", 2, ADMIN_ID,
                        IDEMPOTENCY_KEY, REQUEST_HASH, CORRELATION_ID)))
                .expectError(IdempotencyKeyConflictException.class)
                .verify();

        verifyNoInteractions(eventCacheService, sqsOrderQueueClient);
    }

    @Test
    void shouldRejectAnEventThatIsNotOnSale() {
        when(orderPersistence.findById(EXPECTED_ORDER_ID)).thenReturn(Mono.empty());
        when(eventCacheService.get(eq(EVENT_ID), any()))
                .thenReturn(Mono.just(event(EventStatusEnum.PUBLISHING, 100, 100)));

        StepVerifier.create(useCase().execute(request(2)))
                .expectError(EventNotOnSaleException.class)
                .verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 41})
    void shouldRejectAQuantityOutsideOfTheComplimentaryLimit(int quantity) {
        StepVerifier.create(useCase().execute(request(quantity)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(DomainValidationException.class)
                        .hasMessage("quantity must be between 1 and " + ORDER_POLICY.maxComplimentaryPerRequest()))
                .verify();

        verifyNoInteractions(orderPersistence, eventCacheService, sqsOrderQueueClient);
    }
}
