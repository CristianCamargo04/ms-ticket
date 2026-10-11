package com.ticketing.business.usecase;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.ReserveTicketsRequest;
import reactor.core.publisher.Mono;

/**
 * Reserves (or issues as complimentary) the tickets of a queued order (SQS consumer, idempotent).
 */
public interface ReserveTicketsUseCase {

    /**
     * Selects available tickets and applies the inventory transaction, retrying transient conflicts with backoff;
     * a sold-out or closed event rejects the order.
     *
     * @param request order id and correlation id
     * @return the processed order (unchanged on duplicate delivery); error signals {@code OrderNotFoundException}
     *         (non-retryable) or {@code InventoryConflictException} once the retries are exhausted (retryable)
     */
    Mono<OrderData> execute(ReserveTicketsRequest request);
}
