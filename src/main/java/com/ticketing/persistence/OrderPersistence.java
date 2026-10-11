package com.ticketing.persistence;

import com.ticketing.domain.data.OrderData;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import java.time.Instant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Access to the {@code orders} table.
 */
public interface OrderPersistence {

    /**
     * Inserts an order with the condition {@code attribute_not_exists(orderId)}; writes the sparse deadline
     * attributes when {@code order.deadlineType() != null}.
     *
     * @param order the order to insert
     * @return {@code true} when created, {@code false} when an order with the same id already exists
     */
    Mono<Boolean> insertIfAbsent(OrderData order);

    /**
     * Reads an order with a strongly consistent read.
     *
     * @param orderId the order identifier
     * @return the order, or empty when it does not exist
     */
    Mono<OrderData> findById(String orderId);

    /**
     * Reads the orders whose deadline of the given type is due, querying every shard of the {@code deadline-index}
     * GSI ({@code deadlinePartition = <type>#<shard> AND deadlineAt <= :now}) and merging the results.
     *
     * @param type  the deadline type
     * @param now   the reference instant
     * @param limit maximum orders returned overall
     * @return the due orders
     */
    Flux<OrderData> findDueOrders(DeadlineTypeEnum type, Instant now, int limit);

    /**
     * Conditionally changes the processing status ({@code processingStatus = :expected}); removes the deadline
     * attributes when {@code target} is terminal.
     *
     * @param orderId  the order identifier
     * @param expected the processing status the order must currently have
     * @param target   the new processing status
     * @param reason   the reason to record (e.g. {@code SOLD_OUT}), nullable
     * @param now      update instant
     * @return {@code true} when updated, {@code false} when the condition failed
     */
    Mono<Boolean> transitionProcessingStatus(String orderId, OrderProcessingStatusEnum expected,
                                             OrderProcessingStatusEnum target, String reason, Instant now);

    /**
     * Conditionally moves the deadline of an order ({@code processingStatus = :expected}), keeping its deadline
     * partition.
     *
     * @param orderId    the order identifier
     * @param expected   the processing status the order must currently have
     * @param deadlineAt the new deadline
     * @param now        update instant
     * @return {@code true} when updated, {@code false} when the condition failed
     */
    Mono<Boolean> rescheduleDeadline(String orderId, OrderProcessingStatusEnum expected, Instant deadlineAt,
                                     Instant now);
}
