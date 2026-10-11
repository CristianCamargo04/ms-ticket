package com.ticketing.business.usecase.impl.inventory;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CURRENCY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.EVENT_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.NOW;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.ORDER_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.PRICE;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.PUBLISH_RETRY_POLICY;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.business.usecase.impl.order.ConfirmOrderUseCaseImpl;
import com.ticketing.business.usecase.impl.order.PlaceOrderUseCaseImpl;
import com.ticketing.business.usecase.impl.testing.InMemoryDistributedLockPersistence;
import com.ticketing.business.usecase.impl.testing.InMemoryEventPersistence;
import com.ticketing.business.usecase.impl.testing.InMemoryInventoryTransactionPersistence;
import com.ticketing.business.usecase.impl.testing.InMemoryOrderPersistence;
import com.ticketing.business.usecase.impl.testing.InMemoryTicketPersistence;
import com.ticketing.business.usecase.impl.testing.InMemoryTicketingStore;
import com.ticketing.business.usecase.impl.testing.PassThroughEventCacheService;
import com.ticketing.business.usecase.impl.testing.RecordingSqsOrderQueueClient;
import com.ticketing.client.rest.api.PaymentGatewayChargeApi;
import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.data.SweepResult;
import com.ticketing.domain.data.TicketData;
import com.ticketing.domain.data.TicketTransitionData;
import com.ticketing.domain.exception.IdempotencyKeyConflictException;
import com.ticketing.domain.exception.ReservationExpiredException;
import com.ticketing.domain.external_request.payment.PaymentChargeRequest;
import com.ticketing.domain.internal_request.ConfirmOrderRequest;
import com.ticketing.domain.internal_request.OrderReservationMessage;
import com.ticketing.domain.internal_request.PlaceOrderRequest;
import com.ticketing.domain.internal_request.ReserveTicketsRequest;
import com.ticketing.domain.inventory.RetryPolicy;
import com.ticketing.domain.inventory.SweeperPolicy;
import com.ticketing.domain.order.PaymentOutcome;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import com.ticketing.persistence.OrderPersistence;
import com.ticketing.util.DeterministicIdUtil;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * Races of the order lifecycle against thread-safe in-memory adapters that implement the conditional semantics of
 * implementation spec 6.3 atomically (each adapter call is serializable, like one DynamoDB request, and completes on
 * {@link Schedulers#parallel()}). The ticket availability index lags behind the tickets on purpose, so concurrent
 * workers select stale tickets and the conditional transaction has to arbitrate.
 *
 * <p>Invariants checked after every race: no ticket is ever held by two orders, the event counters always partition
 * the capacity and match the tickets, every ticket transition is audited exactly once, and exactly one competitor
 * wins each conflicting write.
 */
class ReserveTicketsUseCaseImplConcurrencyTest {

    private static final int CAPACITY = 100;
    private static final int COMPETING_ORDERS = 1_000;
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    /** Short real-time backoff with many retries: the contention phase of a sold-out burst is short but intense. */
    private static final RetryPolicy CONTENDED_RETRY = new RetryPolicy(100, Duration.ofMillis(1),
            Duration.ofMillis(10), 0.5);
    /** Same thread-safe random source as the production bean. */
    private static final RandomGenerator RANDOM = () -> ThreadLocalRandom.current().nextLong();
    /** The availability index is refreshed on one read out of three. */
    private static final int INDEX_REFRESH_EVERY = 3;

    private InMemoryTicketingStore store;
    private InMemoryOrderPersistence orderPersistence;
    private InMemoryEventPersistence eventPersistence;
    private InMemoryTicketPersistence ticketPersistence;
    private InMemoryInventoryTransactionPersistence inventoryPersistence;
    private RecordingSqsOrderQueueClient queueClient;

    @BeforeEach
    void setUp() {
        store = new InMemoryTicketingStore();
        orderPersistence = new InMemoryOrderPersistence(store);
        eventPersistence = new InMemoryEventPersistence(store);
        ticketPersistence = new InMemoryTicketPersistence(store, INDEX_REFRESH_EVERY);
        inventoryPersistence = new InMemoryInventoryTransactionPersistence(store);
        queueClient = new RecordingSqsOrderQueueClient();
        store.seedEventWithAvailableTickets(EventData.newPublishing(EVENT_ID, "Concert", "Arena",
                NOW.plus(Duration.ofDays(30)), CAPACITY, PRICE, CURRENCY, NOW.minusSeconds(3600))
                .withStatus(EventStatusEnum.ON_SALE, NOW.minusSeconds(3600)));
    }

    private ReserveTicketsUseCaseImpl reserveTickets(Clock clock) {
        return new ReserveTicketsUseCaseImpl(orderPersistence, eventPersistence, ticketPersistence,
                inventoryPersistence, ORDER_POLICY, CONTENDED_RETRY, clock, RANDOM);
    }

    private static OrderData queued(String orderId, String customerId, int quantity) {
        return OrderData.newQueued(orderId, EVENT_ID, customerId, OrderTypeEnum.STANDARD, quantity, "hash-" + orderId,
                ORDER_POLICY.dispatchDeadline(NOW), NOW);
    }

    @RepeatedTest(3)
    void shouldSellExactlyTheCapacityWhenAThousandOrdersCompeteForAHundredTickets() {
        List<OrderData> competing = IntStream.range(0, COMPETING_ORDERS)
                .mapToObj(i -> queued("order-%04d".formatted(i), "customer-" + i, 1 + i % 3))
                .toList();
        competing.forEach(store::seedOrder);
        ReserveTicketsUseCaseImpl useCase = reserveTickets(CLOCK);

        StepVerifier.create(Flux.fromIterable(competing)
                        .flatMap(order -> useCase.execute(new ReserveTicketsRequest(order.orderId(), "corr"))
                                .subscribeOn(Schedulers.parallel()), COMPETING_ORDERS)
                        .collectList())
                .assertNext(results -> {
                    assertThat(results).hasSize(COMPETING_ORDERS);
                    Map<OrderProcessingStatusEnum, List<OrderData>> byStatus =
                            results.stream().collect(groupingBy(OrderData::processingStatus));
                    assertThat(byStatus).containsOnlyKeys(OrderProcessingStatusEnum.ACTIVE,
                            OrderProcessingStatusEnum.REJECTED);
                    List<OrderData> reserved = byStatus.get(OrderProcessingStatusEnum.ACTIVE);
                    assertThat(reserved).allSatisfy(order -> {
                        assertThat(order.ticketStatus()).isEqualTo(TicketStatusEnum.RESERVED);
                        assertThat(order.ticketIds()).hasSize(order.quantity());
                    });
                    assertThat(byStatus.get(OrderProcessingStatusEnum.REJECTED)).allSatisfy(order -> {
                        assertThat(order.reason()).isEqualTo("SOLD_OUT");
                        assertThat(order.ticketStatus()).isEqualTo(TicketStatusEnum.AVAILABLE);
                        assertThat(order.ticketIds()).isEmpty();
                    });
                    assertThat(reserved.stream().mapToInt(OrderData::quantity).sum())
                            .as("tickets reserved").isEqualTo(CAPACITY);
                    assertThat(reserved.stream().flatMap(order -> order.ticketIds().stream()).toList())
                            .as("no ticket is in two orders").hasSize(CAPACITY).doesNotHaveDuplicates();
                    assertThat(results).allSatisfy(order -> assertThat(store.order(order.orderId())).isEqualTo(order));
                })
                .expectComplete()
                .verify(TIMEOUT);

        assertCountersMatchTickets(CAPACITY, 0, CAPACITY, 0);
        Map<String, OrderData> ordersById = store.orders().stream().collect(toMap(OrderData::orderId,
                Function.identity()));
        assertThat(store.tickets(EVENT_ID)).allSatisfy(ticket -> {
            assertThat(ticket.status()).isEqualTo(TicketStatusEnum.RESERVED);
            assertThat(ordersById.get(ticket.orderId()).ticketIds()).contains(ticket.ticketId());
        });
        assertThat(store.transitions()).hasSize(CAPACITY)
                .allSatisfy(transition -> assertThat(transition.reason()).isEqualTo(TransitionReasonEnum.RESERVATION))
                .extracting(TicketTransitionData::ticketId).doesNotHaveDuplicates();
        assertThat(inventoryPersistence.staleTicketSelectionCount())
                .as("stale selections were arbitrated by the conditional transaction").isPositive();
    }

    @RepeatedTest(20)
    void shouldReserveOnceWhenTheSameMessageIsDeliveredTenTimesConcurrently() {
        OrderData order = queued("duplicated-order", "customer-1", 3);
        store.seedOrder(order);
        ReserveTicketsUseCaseImpl useCase = reserveTickets(CLOCK);
        ReserveTicketsRequest request = new ReserveTicketsRequest(order.orderId(), "corr");

        StepVerifier.create(Flux.range(0, 10)
                        .flatMap(delivery -> useCase.execute(request).subscribeOn(Schedulers.parallel()))
                        .collectList())
                .assertNext(results -> {
                    assertThat(results).hasSize(10).allSatisfy(result -> {
                        assertThat(result.processingStatus()).isEqualTo(OrderProcessingStatusEnum.ACTIVE);
                        assertThat(result.ticketStatus()).isEqualTo(TicketStatusEnum.RESERVED);
                    });
                    assertThat(results).extracting(OrderData::ticketIds).containsOnly(results.getFirst().ticketIds());
                })
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(inventoryPersistence.appliedCount()).as("committed reservations").isEqualTo(1);
        assertCountersMatchTickets(CAPACITY, CAPACITY - 3, 3, 0);
        assertThat(store.tickets(EVENT_ID).stream().filter(ticket -> order.orderId().equals(ticket.orderId())))
                .hasSize(3);
        assertThat(store.transitions()).hasSize(3);
    }

    @RepeatedTest(10)
    void shouldLetExactlyOneOfConfirmationAndExpirationWinAtTheHoldBoundary() {
        Instant holdEnd = NOW;
        List<OrderData> reserved = reserveFiftyOrdersOfTwoTicketsHeldUntil(holdEnd);
        ConfirmOrderUseCaseImpl confirmOrder = new ConfirmOrderUseCaseImpl(orderPersistence, inventoryPersistence,
                queueClient, ORDER_POLICY, PUBLISH_RETRY_POLICY, Clock.fixed(holdEnd.minusMillis(1), ZoneOffset.UTC));
        InMemoryDistributedLockPersistence locks = new InMemoryDistributedLockPersistence(store);
        ReleaseExpiredReservationsUseCaseImpl sweeper = new ReleaseExpiredReservationsUseCaseImpl(locks,
                new LaggingDeadlineIndex(orderPersistence, reserved), inventoryPersistence, new NoChargesGateway(),
                new SweeperPolicy(Duration.ofSeconds(30), 100, 8, "sweeper-1"), Clock.fixed(holdEnd, ZoneOffset.UTC));

        Mono<List<Map.Entry<String, Object>>> confirmations = Flux.fromIterable(reserved)
                .flatMap(order -> confirmOrder.execute(new ConfirmOrderRequest(order.orderId(), order.customerId(),
                                "tok_1", "corr"))
                        .<Object>map(Function.identity())
                        .onErrorResume(Mono::just)
                        .map(outcome -> Map.entry(order.orderId(), outcome))
                        .subscribeOn(Schedulers.parallel()))
                .collectList();

        StepVerifier.create(Mono.zip(confirmations, sweeper.execute().subscribeOn(Schedulers.parallel())))
                .assertNext(race -> {
                    Map<String, Object> outcomes = race.getT1().stream()
                            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
                    List<String> confirmed = outcomes.entrySet().stream()
                            .filter(entry -> entry.getValue() instanceof OrderData).map(Map.Entry::getKey).toList();
                    List<String> expired = outcomes.entrySet().stream()
                            .filter(entry -> entry.getValue() instanceof ReservationExpiredException)
                            .map(Map.Entry::getKey).toList();
                    assertThat(confirmed.size() + expired.size()).as("every confirmation either won or saw the expiry")
                            .isEqualTo(reserved.size());

                    SweepResult sweep = race.getT2();
                    assertThat(sweep.lockAcquired()).isTrue();
                    assertThat(sweep.released()).as("released by the sweeper").isEqualTo(expired.size());
                    assertThat(sweep.failed()).isZero();

                    assertThat(confirmed).allSatisfy(orderId -> assertOrderHolds(orderId,
                            TicketStatusEnum.PENDING_CONFIRMATION, OrderProcessingStatusEnum.ACTIVE));
                    assertThat(expired).allSatisfy(orderId -> {
                        OrderData order = store.order(orderId);
                        assertThat(order.processingStatus()).isEqualTo(OrderProcessingStatusEnum.EXPIRED);
                        assertThat(order.ticketStatus()).isEqualTo(TicketStatusEnum.AVAILABLE);
                    });
                    assertCountersMatchTickets(CAPACITY, 2 * expired.size(), 0, 2 * confirmed.size());
                    assertThat(queueClient.paymentMessages()).hasSize(confirmed.size());
                })
                .expectComplete()
                .verify(TIMEOUT);

        Map<String, List<TicketTransitionData>> history = store.transitions().stream()
                .collect(groupingBy(TicketTransitionData::ticketId));
        assertThat(history).hasSize(CAPACITY).allSatisfy((ticketId, transitions) -> {
            assertThat(transitions).hasSize(2);
            assertThat(transitions.get(0).reason()).isEqualTo(TransitionReasonEnum.RESERVATION);
            assertThat(transitions.get(1).reason()).isIn(TransitionReasonEnum.CUSTOMER_CONFIRMATION,
                    TransitionReasonEnum.HOLD_EXPIRED);
        });
        assertThat(locks.isHeld(ReleaseExpiredReservationsUseCaseImpl.LOCK_NAME)).as("lease released").isFalse();
    }

    @RepeatedTest(10)
    void shouldPersistOneOrderWhenTheSameIdempotencyKeyIsUsedFiftyTimesInParallel() {
        PlaceOrderUseCaseImpl placeOrder = placeOrder();
        PlaceOrderRequest request = new PlaceOrderRequest(EVENT_ID, "customer-1", 2, "idem-key-0001", "hash-1",
                "corr-1");
        String expectedOrderId = DeterministicIdUtil.nameBasedId("customer-1:idem-key-0001");

        StepVerifier.create(Flux.range(0, 50)
                        .flatMap(attempt -> placeOrder.execute(request).subscribeOn(Schedulers.parallel()))
                        .collectList())
                .assertNext(results -> assertThat(results).hasSize(50).allSatisfy(order -> {
                    assertThat(order.orderId()).isEqualTo(expectedOrderId);
                    assertThat(order).isEqualTo(results.getFirst());
                }))
                .expectComplete()
                .verify(TIMEOUT);

        assertThat(store.orders()).singleElement().satisfies(order -> {
            assertThat(order.orderId()).isEqualTo(expectedOrderId);
            assertThat(order.processingStatus()).isEqualTo(OrderProcessingStatusEnum.QUEUED);
        });
        assertThat(queueClient.reservationMessages()).isNotEmpty()
                .extracting(OrderReservationMessage::orderId).containsOnly(expectedOrderId);
        assertCountersMatchTickets(CAPACITY, CAPACITY, 0, 0);
    }

    @Test
    void shouldLetOnlyOnePayloadWinWhenAKeyIsReusedConcurrentlyWithDifferentPayloads() {
        PlaceOrderUseCaseImpl placeOrder = placeOrder();

        StepVerifier.create(Flux.range(0, 40)
                        .flatMap(attempt -> placeOrder.execute(new PlaceOrderRequest(EVENT_ID, "customer-1",
                                        1 + attempt % 2, "idem-key-0002", "hash-" + attempt % 2, "corr"))
                                .<Object>map(Function.identity())
                                .onErrorResume(Mono::just)
                                .subscribeOn(Schedulers.parallel()))
                        .collectList())
                .assertNext(results -> {
                    OrderData winner = store.orders().getFirst();
                    assertThat(store.orders()).hasSize(1);
                    assertThat(results).hasSize(40).allSatisfy(result -> {
                        if (result instanceof OrderData order) {
                            assertThat(order).isEqualTo(winner);
                        } else {
                            assertThat(result).isInstanceOf(IdempotencyKeyConflictException.class);
                        }
                    });
                    assertThat(results).filteredOn(OrderData.class::isInstance).hasSize(20);
                })
                .expectComplete()
                .verify(TIMEOUT);
    }

    private PlaceOrderUseCaseImpl placeOrder() {
        return new PlaceOrderUseCaseImpl(orderPersistence, eventPersistence, new PassThroughEventCacheService(),
                queueClient, ORDER_POLICY, PUBLISH_RETRY_POLICY, CLOCK);
    }

    /** Reserves 50 orders of 2 tickets (the whole capacity) whose hold ends at {@code holdEnd}. */
    private List<OrderData> reserveFiftyOrdersOfTwoTicketsHeldUntil(Instant holdEnd) {
        List<OrderData> queued = IntStream.range(0, CAPACITY / 2)
                .mapToObj(i -> queued("held-%02d".formatted(i), "customer-" + i, 2))
                .toList();
        queued.forEach(store::seedOrder);
        ReserveTicketsUseCaseImpl reserve = reserveTickets(Clock.fixed(holdEnd.minus(ORDER_POLICY.holdTtl()),
                ZoneOffset.UTC));
        List<OrderData> reserved = Flux.fromIterable(queued)
                .flatMap(order -> reserve.execute(new ReserveTicketsRequest(order.orderId(), "corr"))
                        .subscribeOn(Schedulers.parallel()))
                .collectList()
                .block(TIMEOUT);
        assertThat(reserved).hasSize(CAPACITY / 2).allSatisfy(order -> {
            assertThat(order.ticketStatus()).isEqualTo(TicketStatusEnum.RESERVED);
            assertThat(order.holdExpiresAt()).isEqualTo(holdEnd);
        });
        assertCountersMatchTickets(CAPACITY, 0, CAPACITY, 0);
        return reserved;
    }

    private void assertOrderHolds(String orderId, TicketStatusEnum ticketStatus,
                                  OrderProcessingStatusEnum processingStatus) {
        OrderData order = store.order(orderId);
        assertThat(order.ticketStatus()).isEqualTo(ticketStatus);
        assertThat(order.processingStatus()).isEqualTo(processingStatus);
        List<TicketData> tickets = store.tickets(EVENT_ID).stream()
                .filter(ticket -> order.ticketIds().contains(ticket.ticketId())).toList();
        assertThat(tickets).hasSize(order.quantity()).allSatisfy(ticket -> {
            assertThat(ticket.status()).isEqualTo(ticketStatus);
            assertThat(ticket.orderId()).isEqualTo(orderId);
        });
    }

    /** The event counters partition the capacity and match the persisted ticket statuses. */
    private void assertCountersMatchTickets(int capacity, int available, int reserved, int pending) {
        EventData event = store.event(EVENT_ID);
        Map<TicketStatusEnum, Long> tickets = store.tickets(EVENT_ID).stream()
                .collect(groupingBy(TicketData::status, counting()));
        assertThat(event.availableCount()).as("available").isEqualTo(available);
        assertThat(event.reservedCount()).as("reserved").isEqualTo(reserved);
        assertThat(event.pendingCount()).as("pending").isEqualTo(pending);
        assertThat(event.availableCount() + event.reservedCount() + event.pendingCount() + event.soldCount()
                + event.complimentaryCount()).as("counters partition the capacity").isEqualTo(capacity);
        assertThat(tickets.getOrDefault(TicketStatusEnum.AVAILABLE, 0L)).isEqualTo(available);
        assertThat(tickets.getOrDefault(TicketStatusEnum.RESERVED, 0L)).isEqualTo(reserved);
        assertThat(tickets.getOrDefault(TicketStatusEnum.PENDING_CONFIRMATION, 0L)).isEqualTo(pending);
        assertThat(store.tickets(EVENT_ID).stream().filter(ticket -> ticket.status() == TicketStatusEnum.AVAILABLE))
                .allSatisfy(ticket -> assertThat(ticket.orderId()).isNull());
    }

    /**
     * Deadline index that still lists the reserved orders as due for hold expiration (GSI lag), so the sweeper
     * attempts every release while the customers confirm: the race is decided by the conditional writes alone.
     */
    private static final class LaggingDeadlineIndex implements OrderPersistence {

        private final OrderPersistence delegate;
        private final List<OrderData> laggingHoldExpirations;

        private LaggingDeadlineIndex(OrderPersistence delegate, List<OrderData> laggingHoldExpirations) {
            this.delegate = delegate;
            this.laggingHoldExpirations = List.copyOf(laggingHoldExpirations);
        }

        @Override
        public Mono<Boolean> insertIfAbsent(OrderData order) {
            return delegate.insertIfAbsent(order);
        }

        @Override
        public Mono<OrderData> findById(String orderId) {
            return delegate.findById(orderId);
        }

        @Override
        public Flux<OrderData> findDueOrders(DeadlineTypeEnum type, Instant now, int limit) {
            return type == DeadlineTypeEnum.HOLD_EXPIRATION
                    ? Flux.fromIterable(laggingHoldExpirations).take(limit)
                    : delegate.findDueOrders(type, now, limit);
        }

        @Override
        public Mono<Boolean> transitionProcessingStatus(String orderId, OrderProcessingStatusEnum expected,
                                                        OrderProcessingStatusEnum target, String reason,
                                                        Instant now) {
            return delegate.transitionProcessingStatus(orderId, expected, target, reason, now);
        }

        @Override
        public Mono<Boolean> rescheduleDeadline(String orderId, OrderProcessingStatusEnum expected,
                                               Instant deadlineAt, Instant now) {
            return delegate.rescheduleDeadline(orderId, expected, deadlineAt, now);
        }
    }

    /** No order of these races reaches the payment step. */
    private static final class NoChargesGateway implements PaymentGatewayChargeApi {

        @Override
        public Mono<PaymentOutcome> charge(PaymentChargeRequest request) {
            return Mono.error(new AssertionError("unexpected charge"));
        }

        @Override
        public Mono<PaymentOutcome> findCharge(String idempotencyKey) {
            return Mono.just(new PaymentOutcome.NotFound());
        }
    }
}
