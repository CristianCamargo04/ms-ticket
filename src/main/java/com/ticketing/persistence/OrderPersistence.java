package com.ticketing.persistence;

import com.ticketing.domain.data.OrderData;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import java.time.Instant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OrderPersistence {
    Mono<Boolean> insertIfAbsent(OrderData order);

    Mono<OrderData> findById(String orderId);

    Flux<OrderData> findDueOrders(DeadlineTypeEnum type, Instant now, int limit);

    Mono<Boolean> transitionProcessingStatus(String orderId, OrderProcessingStatusEnum expected,
                                             OrderProcessingStatusEnum target, String reason, Instant now);

    Mono<Boolean> rescheduleDeadline(String orderId, OrderProcessingStatusEnum expected, Instant deadlineAt,
                                     Instant now);
}
