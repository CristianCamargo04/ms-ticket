package com.ticketing.persistence.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.domain.data.OrderData;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.persistence.impl.support.DeadlinePartitioner;
import com.ticketing.persistence.support.PersistenceTestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class OrderPersistenceImplIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final Instant SWEEP_INSTANT = Instant.parse("2020-01-01T00:00:00Z");

    private static PersistenceTestContext context;

    @BeforeAll
    static void createTables() {
        context = PersistenceTestContext.create();
    }

    @Test
    void shouldInsertAnOrderOnlyOnce() {
        OrderData order = queued(UUID.randomUUID().toString(), PersistenceTestContext.now().plusSeconds(30));

        StepVerifier.create(context.orders().insertIfAbsent(order)).expectNext(true).verifyComplete();
        StepVerifier.create(context.orders().insertIfAbsent(order)).expectNext(false).verifyComplete();
        StepVerifier.create(context.orders().findById(order.orderId())).expectNext(order).verifyComplete();
        StepVerifier.create(context.orders().findById(UUID.randomUUID().toString())).verifyComplete();
    }

    @Test
    void shouldFindDueOrdersAcrossEveryShardInDeadlineOrder() {
        List<OrderData> due = IntStream.range(0, 12)
                .mapToObj(index -> queued("due-order-" + index, SWEEP_INSTANT.minusSeconds(12 - index)))
                .toList();
        List<OrderData> notDue = IntStream.range(0, 3)
                .mapToObj(index -> queued("future-order-" + index, SWEEP_INSTANT.plusSeconds(index + 1)))
                .toList();
        Flux.concat(Flux.fromIterable(due), Flux.fromIterable(notDue))
                .flatMap(order -> context.orders().insertIfAbsent(order))
                .blockLast(TIMEOUT);
        DeadlinePartitioner partitioner = new DeadlinePartitioner(context.tableNames());
        assertThat(due.stream().map(order -> partitioner.partitionOf(DeadlineTypeEnum.DISPATCH, order.orderId()))
                .distinct().count()).as("shards covered by the due orders").isGreaterThan(2);

        assertThat(context.orders().findDueOrders(DeadlineTypeEnum.DISPATCH, SWEEP_INSTANT, 100).collectList()
                .block(TIMEOUT)).containsExactlyElementsOf(due);
        assertThat(context.orders().findDueOrders(DeadlineTypeEnum.DISPATCH, SWEEP_INSTANT, 5).collectList()
                .block(TIMEOUT)).containsExactlyElementsOf(due.subList(0, 5));
        assertThat(context.orders().findDueOrders(DeadlineTypeEnum.HOLD_EXPIRATION, SWEEP_INSTANT, 100)
                .collectList().block(TIMEOUT)).isEmpty();
        assertThat(context.orders().findDueOrders(DeadlineTypeEnum.DISPATCH, SWEEP_INSTANT, 0).collectList()
                .block(TIMEOUT)).isEmpty();
    }

    @Test
    void shouldTransitionTheProcessingStatusConditionally() {
        Instant deadline = PersistenceTestContext.now().plus(Duration.ofDays(1));
        OrderData order = queued(UUID.randomUUID().toString(), deadline);
        context.orders().insertIfAbsent(order).block(TIMEOUT);
        Instant now = PersistenceTestContext.now();

        StepVerifier.create(context.orders().transitionProcessingStatus(order.orderId(),
                OrderProcessingStatusEnum.QUEUED, OrderProcessingStatusEnum.REJECTED, "SOLD_OUT", now))
                .expectNext(true).verifyComplete();
        StepVerifier.create(context.orders().transitionProcessingStatus(order.orderId(),
                OrderProcessingStatusEnum.QUEUED, OrderProcessingStatusEnum.CANCELLED, null, now))
                .expectNext(false).verifyComplete();

        OrderData persisted = context.order(order.orderId());
        assertThat(persisted).isEqualTo(order.withProcessingStatus(OrderProcessingStatusEnum.REJECTED, "SOLD_OUT",
                now));
        assertThat(persisted.deadlineAt()).isNull();
        assertThat(context.orders().findDueOrders(DeadlineTypeEnum.DISPATCH, deadline.plusSeconds(1), 100)
                .map(OrderData::orderId).collectList().block(TIMEOUT)).doesNotContain(order.orderId());
    }

    @Test
    void shouldClearTheReasonWhenNoneIsGiven() {
        OrderData order = queued(UUID.randomUUID().toString(), PersistenceTestContext.now().plusSeconds(30));
        context.orders().insertIfAbsent(order).block(TIMEOUT);
        Instant now = PersistenceTestContext.now();

        assertThat(context.orders().transitionProcessingStatus(order.orderId(), OrderProcessingStatusEnum.QUEUED,
                OrderProcessingStatusEnum.CANCELLED, null, now).block(TIMEOUT)).isTrue();
        assertThat(context.order(order.orderId()))
                .isEqualTo(order.withProcessingStatus(OrderProcessingStatusEnum.CANCELLED, null, now));
    }

    @Test
    void shouldRescheduleTheDeadlineOfAQueuedOrder() {
        Instant oldDeadline = PersistenceTestContext.now().plus(Duration.ofDays(1));
        OrderData order = queued(UUID.randomUUID().toString(), oldDeadline);
        context.orders().insertIfAbsent(order).block(TIMEOUT);
        Instant now = PersistenceTestContext.now();
        Instant newDeadline = oldDeadline.plus(Duration.ofDays(10));

        StepVerifier.create(context.orders().rescheduleDeadline(order.orderId(), OrderProcessingStatusEnum.QUEUED,
                newDeadline, now)).expectNext(true).verifyComplete();
        StepVerifier.create(context.orders().rescheduleDeadline(order.orderId(), OrderProcessingStatusEnum.ACTIVE,
                newDeadline, now)).expectNext(false).verifyComplete();

        assertThat(context.order(order.orderId())).isEqualTo(order.withDeadline(newDeadline, now));
        assertThat(context.orders().findDueOrders(DeadlineTypeEnum.DISPATCH, oldDeadline.plusSeconds(1), 100)
                .map(OrderData::orderId).collectList().block(TIMEOUT)).doesNotContain(order.orderId());
        assertThat(context.orders().findDueOrders(DeadlineTypeEnum.DISPATCH, newDeadline, 100)
                .map(OrderData::orderId).collectList().block(TIMEOUT)).contains(order.orderId());
    }

    private static OrderData queued(String orderId, Instant deadline) {
        Instant createdAt = PersistenceTestContext.now();
        return OrderData.newQueued(orderId, "event-1", "customer-1", OrderTypeEnum.STANDARD, 2, "hash", deadline,
                createdAt);
    }
}
