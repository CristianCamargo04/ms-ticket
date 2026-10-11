package com.ticketing.business.usecase;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.PlaceOrderRequest;
import reactor.core.publisher.Mono;

/**
 * Accepts a purchase order and queues its reservation (API fast path, never touches the inventory).
 */
public interface PlaceOrderUseCase {

    /**
     * Idempotently creates a {@code QUEUED} order (id derived from customer and idempotency key) and publishes its
     * reservation message.
     *
     * @param request the order request
     * @return the queued (or previously created) order; error signals {@code DomainValidationException},
     *         {@code IdempotencyKeyConflictException}, {@code EventNotFoundException},
     *         {@code EventNotOnSaleException}, {@code InsufficientInventoryException}
     */
    Mono<OrderData> execute(PlaceOrderRequest request);
}
