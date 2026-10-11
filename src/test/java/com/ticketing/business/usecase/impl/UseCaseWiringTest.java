package com.ticketing.business.usecase.impl;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CLOCK;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.EVENT_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.EVENT_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.ORDER_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.ORDER_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.SWEEPER_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.availableTicket;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.onSaleEvent;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.queuedOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.business.usecase.CancelOrderUseCase;
import com.ticketing.business.usecase.ConfirmOrderUseCase;
import com.ticketing.business.usecase.CreateEventUseCase;
import com.ticketing.business.usecase.GetEventAvailabilityUseCase;
import com.ticketing.business.usecase.GetEventUseCase;
import com.ticketing.business.usecase.GetOrderStatusUseCase;
import com.ticketing.business.usecase.GetTicketHistoryUseCase;
import com.ticketing.business.usecase.IssueComplimentaryTicketsUseCase;
import com.ticketing.business.usecase.ListEventsUseCase;
import com.ticketing.business.usecase.PlaceOrderUseCase;
import com.ticketing.business.usecase.ProcessOrderPaymentUseCase;
import com.ticketing.business.usecase.ReleaseExpiredReservationsUseCase;
import com.ticketing.business.usecase.RepublishStaleOrdersUseCase;
import com.ticketing.business.usecase.ReserveTicketsUseCase;
import com.ticketing.business.usecase.StreamEventAvailabilityUseCase;
import com.ticketing.business.usecase.impl.event.CreateEventUseCaseImpl;
import com.ticketing.business.usecase.impl.event.GetEventAvailabilityUseCaseImpl;
import com.ticketing.business.usecase.impl.event.GetEventUseCaseImpl;
import com.ticketing.business.usecase.impl.event.GetTicketHistoryUseCaseImpl;
import com.ticketing.business.usecase.impl.event.ListEventsUseCaseImpl;
import com.ticketing.business.usecase.impl.event.StreamEventAvailabilityUseCaseImpl;
import com.ticketing.business.usecase.impl.inventory.ProcessOrderPaymentUseCaseImpl;
import com.ticketing.business.usecase.impl.inventory.ReleaseExpiredReservationsUseCaseImpl;
import com.ticketing.business.usecase.impl.inventory.RepublishStaleOrdersUseCaseImpl;
import com.ticketing.business.usecase.impl.inventory.ReserveTicketsUseCaseImpl;
import com.ticketing.business.usecase.impl.order.CancelOrderUseCaseImpl;
import com.ticketing.business.usecase.impl.order.ConfirmOrderUseCaseImpl;
import com.ticketing.business.usecase.impl.order.GetOrderStatusUseCaseImpl;
import com.ticketing.business.usecase.impl.order.IssueComplimentaryTicketsUseCaseImpl;
import com.ticketing.business.usecase.impl.order.PlaceOrderUseCaseImpl;
import com.ticketing.cache.EventCacheService;
import com.ticketing.client.rest.api.PaymentGatewayChargeApi;
import com.ticketing.client.sdk.SqsOrderQueueClient;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.event.EventPolicy;
import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.exception.MessagePublishingException;
import com.ticketing.domain.internal_request.PlaceOrderRequest;
import com.ticketing.domain.internal_request.ReserveTicketsRequest;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import com.ticketing.domain.inventory.RetryPolicy;
import com.ticketing.domain.inventory.SweeperPolicy;
import com.ticketing.domain.order.OrderPolicy;
import com.ticketing.persistence.DistributedLockPersistence;
import com.ticketing.persistence.EventPersistence;
import com.ticketing.persistence.InventoryTransactionPersistence;
import com.ticketing.persistence.OrderPersistence;
import com.ticketing.persistence.TicketPersistence;
import com.ticketing.persistence.TicketTransitionPersistence;
import com.ticketing.util.DeterministicIdUtil;
import java.time.Clock;
import java.time.Duration;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class UseCaseWiringTest {
    private static final RetryPolicy INVENTORY_RETRY = new RetryPolicy(2, Duration.ofMillis(1), Duration.ofMillis(1),
            0.0);

    private static final RetryPolicy PUBLISH_RETRY = new RetryPolicy(0, Duration.ofMillis(1), Duration.ofMillis(1),
            0.0);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(Clock.class, () -> CLOCK)
            .withBean(RandomGenerator.class, () -> new SplittableRandom(42))
            .withBean(OrderPolicy.class, () -> ORDER_POLICY)
            .withBean(EventPolicy.class, () -> EVENT_POLICY)
            .withBean(SweeperPolicy.class, () -> SWEEPER_POLICY)
            .withBean("inventoryRetryPolicy", RetryPolicy.class, () -> INVENTORY_RETRY)
            .withBean("publishRetryPolicy", RetryPolicy.class, () -> PUBLISH_RETRY)
            .withBean(EventPersistence.class, () -> mock(EventPersistence.class))
            .withBean(TicketPersistence.class, () -> mock(TicketPersistence.class))
            .withBean(OrderPersistence.class, () -> mock(OrderPersistence.class))
            .withBean(InventoryTransactionPersistence.class, () -> mock(InventoryTransactionPersistence.class))
            .withBean(TicketTransitionPersistence.class, () -> mock(TicketTransitionPersistence.class))
            .withBean(DistributedLockPersistence.class, () -> mock(DistributedLockPersistence.class))
            .withBean(EventCacheService.class, () -> mock(EventCacheService.class))
            .withBean(SqsOrderQueueClient.class, () -> mock(SqsOrderQueueClient.class))
            .withBean(PaymentGatewayChargeApi.class, () -> mock(PaymentGatewayChargeApi.class))
            .withBean(CreateEventUseCaseImpl.class)
            .withBean(GetEventUseCaseImpl.class)
            .withBean(ListEventsUseCaseImpl.class)
            .withBean(GetEventAvailabilityUseCaseImpl.class)
            .withBean(StreamEventAvailabilityUseCaseImpl.class)
            .withBean(GetTicketHistoryUseCaseImpl.class)
            .withBean(PlaceOrderUseCaseImpl.class)
            .withBean(IssueComplimentaryTicketsUseCaseImpl.class)
            .withBean(GetOrderStatusUseCaseImpl.class)
            .withBean(ConfirmOrderUseCaseImpl.class)
            .withBean(CancelOrderUseCaseImpl.class)
            .withBean(ReserveTicketsUseCaseImpl.class)
            .withBean(ProcessOrderPaymentUseCaseImpl.class)
            .withBean(ReleaseExpiredReservationsUseCaseImpl.class)
            .withBean(RepublishStaleOrdersUseCaseImpl.class);

    @Test
    void shouldWireEveryUseCaseThroughConstructorInjection() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CreateEventUseCase.class);
            assertThat(context).hasSingleBean(GetEventUseCase.class);
            assertThat(context).hasSingleBean(ListEventsUseCase.class);
            assertThat(context).hasSingleBean(GetEventAvailabilityUseCase.class);
            assertThat(context).hasSingleBean(StreamEventAvailabilityUseCase.class);
            assertThat(context).hasSingleBean(GetTicketHistoryUseCase.class);
            assertThat(context).hasSingleBean(PlaceOrderUseCase.class);
            assertThat(context).hasSingleBean(IssueComplimentaryTicketsUseCase.class);
            assertThat(context).hasSingleBean(GetOrderStatusUseCase.class);
            assertThat(context).hasSingleBean(ConfirmOrderUseCase.class);
            assertThat(context).hasSingleBean(CancelOrderUseCase.class);
            assertThat(context).hasSingleBean(ReserveTicketsUseCase.class);
            assertThat(context).hasSingleBean(ProcessOrderPaymentUseCase.class);
            assertThat(context).hasSingleBean(ReleaseExpiredReservationsUseCase.class);
            assertThat(context).hasSingleBean(RepublishStaleOrdersUseCase.class);
        });
    }

    @Test
    void shouldInjectThePublishRetryPolicyIntoPlaceOrder() {
        runner.run(context -> {
            String orderId = DeterministicIdUtil.nameBasedId("customer-1:key-12345678");
            OrderPersistence orders = context.getBean(OrderPersistence.class);
            SqsOrderQueueClient queue = context.getBean(SqsOrderQueueClient.class);
            when(orders.findById(orderId)).thenReturn(Mono.empty());
            when(orders.insertIfAbsent(any())).thenReturn(Mono.just(true));
            when(context.getBean(EventCacheService.class).get(eq(EVENT_ID), any()))
                    .thenReturn(Mono.just(onSaleEvent(100, 100)));
            when(queue.publishReservationRequested(any()))
                    .thenReturn(Mono.error(new MessagePublishingException("down", new RuntimeException())));

            StepVerifier.create(context.getBean(PlaceOrderUseCase.class).execute(new PlaceOrderRequest(EVENT_ID,
                            "customer-1", 1, "key-12345678", "hash", "corr")))
                    .expectNextCount(1)
                    .expectComplete()
                    .verify(TIMEOUT);

            verify(queue, times(PUBLISH_RETRY.maxAttempts() + 1)).publishReservationRequested(any());
        });
    }

    @Test
    void shouldInjectTheInventoryRetryPolicyIntoReserveTickets() {
        runner.run(context -> {
            OrderData queued = queuedOrder(1);
            InventoryTransactionPersistence inventory = context.getBean(InventoryTransactionPersistence.class);
            when(context.getBean(OrderPersistence.class).findById(ORDER_ID)).thenReturn(Mono.just(queued));
            when(context.getBean(EventPersistence.class).findById(EVENT_ID))
                    .thenReturn(Mono.just(onSaleEvent(100, 100)));
            when(context.getBean(TicketPersistence.class).findAvailable(eq(EVENT_ID), anyString(), anyInt()))
                    .thenAnswer(invocation -> Flux.just(availableTicket(1), availableTicket(2)));
            when(inventory.reserve(any())).thenReturn(Mono.just(new InventoryWriteOutcome.StaleTicketSelection()));

            StepVerifier.create(context.getBean(ReserveTicketsUseCase.class)
                            .execute(new ReserveTicketsRequest(ORDER_ID, "corr")))
                    .expectError(InventoryConflictException.class)
                    .verify(TIMEOUT);

            verify(inventory, times(INVENTORY_RETRY.maxAttempts() + 1)).reserve(any());
        });
    }
}
