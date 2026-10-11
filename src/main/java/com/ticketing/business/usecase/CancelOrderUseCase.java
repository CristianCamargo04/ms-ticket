package com.ticketing.business.usecase;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.CancelOrderRequest;
import reactor.core.publisher.Mono;

/**
 * Cancels a queued or reserved order at the customer's request (idempotent).
 */
public interface CancelOrderUseCase {

    /**
     * Cancels the order and releases its reserved tickets.
     *
     * @param request order id and customer
     * @return the cancelled order; error signals {@code OrderNotFoundException}, {@code InvalidOrderStateException}
     */
    Mono<OrderData> execute(CancelOrderRequest request);
}
