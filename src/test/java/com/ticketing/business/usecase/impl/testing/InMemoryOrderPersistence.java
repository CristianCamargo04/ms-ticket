package com.ticketing.business.usecase.impl.testing;

import com.ticketing.domain.data.OrderData;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.persistence.OrderPersistence;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class InMemoryOrderPersistence implements OrderPersistence {
    private final InMemoryTicketingStore store;

    public InMemoryOrderPersistence(InMemoryTicketingStore store) {
        this.store = store;
    }

    @Override
    public Mono<Boolean> insertIfAbsent(OrderData order) {
        return store.atomically(() -> store.ordersTable().putIfAbsent(order.orderId(), order) == null);
    }

    @Override
    public Mono<OrderData> findById(String orderId) {
        return store.atomically(() -> store.ordersTable().get(orderId));
    }

    @Override
    public Flux<OrderData> findDueOrders(DeadlineTypeEnum type, Instant now, int limit) {
        return store.atomically(() -> store.ordersTable().values().stream()
                        .filter(order -> order.deadlineType() == type)
                        .filter(order -> order.deadlineAt() != null && !order.deadlineAt().isAfter(now))
                        .sorted(Comparator.comparing(OrderData::deadlineAt))
                        .limit(limit)
                        .toList())
                .flatMapIterable(List::copyOf);
    }

    @Override
    public Mono<Boolean> transitionProcessingStatus(String orderId, OrderProcessingStatusEnum expected,
                                                    OrderProcessingStatusEnum target, String reason, Instant now) {
        return store.atomically(() -> {
            OrderData order = store.ordersTable().get(orderId);
            if (order == null || order.processingStatus() != expected) {
                return false;
            }
            store.ordersTable().put(orderId, order.withProcessingStatus(target, reason, now));
            return true;
        });
    }

    @Override
    public Mono<Boolean> rescheduleDeadline(String orderId, OrderProcessingStatusEnum expected, Instant deadlineAt,
                                           Instant now) {
        return store.atomically(() -> {
            OrderData order = store.ordersTable().get(orderId);
            if (order == null || order.processingStatus() != expected) {
                return false;
            }
            store.ordersTable().put(orderId, order.withDeadline(deadlineAt, now));
            return true;
        });
    }
}
