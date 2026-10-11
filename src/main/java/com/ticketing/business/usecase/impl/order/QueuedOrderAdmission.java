package com.ticketing.business.usecase.impl.order;

import com.ticketing.cache.EventCacheService;
import com.ticketing.client.sdk.SqsOrderQueueClient;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.domain.exception.EventNotFoundException;
import com.ticketing.domain.exception.EventNotOnSaleException;
import com.ticketing.domain.exception.IdempotencyKeyConflictException;
import com.ticketing.domain.exception.InsufficientInventoryException;
import com.ticketing.domain.exception.TicketingException;
import com.ticketing.domain.internal_request.OrderReservationMessage;
import com.ticketing.domain.inventory.RetryPolicy;
import com.ticketing.domain.order.OrderPolicy;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.persistence.EventPersistence;
import com.ticketing.persistence.OrderPersistence;
import com.ticketing.util.ReactiveRetryUtil;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.retry.RetryBackoffSpec;

@Slf4j
final class QueuedOrderAdmission {

    private final OrderPersistence orderPersistence;
    private final EventPersistence eventPersistence;
    private final EventCacheService eventCacheService;
    private final SqsOrderQueueClient sqsOrderQueueClient;
    private final OrderPolicy orderPolicy;
    private final RetryBackoffSpec publishRetry;
    private final Clock clock;

    QueuedOrderAdmission(OrderPersistence orderPersistence, EventPersistence eventPersistence,
                         EventCacheService eventCacheService, SqsOrderQueueClient sqsOrderQueueClient,
                         OrderPolicy orderPolicy, RetryPolicy publishRetryPolicy, Clock clock) {
        this.orderPersistence = orderPersistence;
        this.eventPersistence = eventPersistence;
        this.eventCacheService = eventCacheService;
        this.sqsOrderQueueClient = sqsOrderQueueClient;
        this.orderPolicy = orderPolicy;
        this.publishRetry = ReactiveRetryUtil.backoff(publishRetryPolicy.maxAttempts(),
                publishRetryPolicy.minBackoff(), publishRetryPolicy.maxBackoff(), publishRetryPolicy.jitter(),
                TicketingException::isRetryable);
        this.clock = clock;
    }

    static void requireQuantity(int quantity, int max) {
        if (quantity < 1 || quantity > max) {
            throw new DomainValidationException("quantity must be between 1 and " + max);
        }
    }

    Mono<OrderData> admit(Admission admission) {
        return Mono.defer(() -> orderPersistence.findById(admission.orderId()))
                .flatMap(existing -> replay(existing, admission))
                .switchIfEmpty(Mono.defer(() -> create(admission)));
    }

    private Mono<OrderData> create(Admission admission) {
        return eventCacheService.get(admission.eventId(), eventPersistence::findById)
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(admission.eventId())))
                .flatMap(event -> {
                    if (!event.isOnSale()) {
                        return Mono.error(new EventNotOnSaleException(event.eventId(), event.status()));
                    }
                    if (admission.softAvailabilityCheck() && event.availableCount() == 0) {
                        return Mono.error(new InsufficientInventoryException(event.eventId(), admission.quantity(),
                                event.availableCount()));
                    }
                    Instant now = clock.instant();
                    OrderData order = OrderData.newQueued(admission.orderId(), admission.eventId(),
                            admission.customerId(), admission.type(), admission.quantity(), admission.requestHash(),
                            orderPolicy.dispatchDeadline(now), now);
                    return orderPersistence.insertIfAbsent(order)
                            .flatMap(created -> created ? accepted(order, admission) : concurrentDuplicate(admission));
                });
    }

    private Mono<OrderData> accepted(OrderData order, Admission admission) {
        log.info("Order {} ({}) queued for event {}", order.orderId(), order.type(), order.eventId());
        return publishReservation(order, admission.correlationId()).thenReturn(order);
    }

    private Mono<OrderData> concurrentDuplicate(Admission admission) {
        log.debug("Order {} was created by a concurrent duplicate request", admission.orderId());
        return Mono.defer(() -> orderPersistence.findById(admission.orderId()))
                .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                        "Order " + admission.orderId() + " exists but could not be read")))
                .flatMap(existing -> replay(existing, admission));
    }

    private Mono<OrderData> replay(OrderData existing, Admission admission) {
        if (!isSameOrder(existing, admission)) {
            return Mono.error(new IdempotencyKeyConflictException(admission.idempotencyKey()));
        }
        if (existing.processingStatus() == OrderProcessingStatusEnum.QUEUED) {
            return publishReservation(existing, admission.correlationId()).thenReturn(existing);
        }
        return Mono.just(existing);
    }

    private static boolean isSameOrder(OrderData existing, Admission admission) {
        return existing.matchesRequest(admission.requestHash())
                && existing.eventId().equals(admission.eventId())
                && existing.isOwnedBy(admission.customerId())
                && existing.type() == admission.type()
                && existing.quantity() == admission.quantity();
    }

    private Mono<Void> publishReservation(OrderData order, String correlationId) {
        return Mono.defer(() -> sqsOrderQueueClient.publishReservationRequested(new OrderReservationMessage(
                        order.orderId(), order.eventId(), correlationIdOrOrderId(correlationId, order),
                        clock.instant())))
                .retryWhen(publishRetry)
                .doOnNext(messageId -> log.debug("Reservation message {} published for order {}", messageId,
                        order.orderId()))
                .then()
                .onErrorResume(error -> {
                    log.warn("Reservation message of order {} not published, the dispatch sweeper will republish"
                            + " it: {}", order.orderId(), error.toString());
                    return Mono.empty();
                });
    }

    private static String correlationIdOrOrderId(String correlationId, OrderData order) {
        return correlationId == null || correlationId.isBlank() ? order.orderId() : correlationId;
    }

    record Admission(String orderId, String eventId, String customerId, OrderTypeEnum type, int quantity,
                     String idempotencyKey, String requestHash, String correlationId,
                     boolean softAvailabilityCheck) {
    }
}
