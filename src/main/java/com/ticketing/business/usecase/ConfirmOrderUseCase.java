package com.ticketing.business.usecase;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.ConfirmOrderRequest;
import reactor.core.publisher.Mono;

/**
 * Confirms a reservation and requests its payment (idempotent).
 */
public interface ConfirmOrderUseCase {

    /**
     * Moves the reserved tickets to {@code PENDING_CONFIRMATION} and publishes the payment message.
     *
     * @param request order id, customer and payment token
     * @return the order pending confirmation (or already sold); error signals {@code OrderNotFoundException},
     *         {@code InvalidOrderStateException}, {@code ReservationExpiredException},
     *         {@code InventoryConflictException}
     */
    Mono<OrderData> execute(ConfirmOrderRequest request);
}
