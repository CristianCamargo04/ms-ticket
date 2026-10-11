package com.ticketing.business.usecase;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.ProcessOrderPaymentRequest;
import reactor.core.publisher.Mono;

/**
 * Charges a confirmed order and finalizes or releases it (SQS consumer, idempotent).
 */
public interface ProcessOrderPaymentUseCase {

    /**
     * Charges the order (idempotency key = order id); approved finalizes the sale, declined releases the tickets.
     *
     * @param request order id, payment token and correlation id
     * @return the processed order (unchanged when not pending confirmation); error signals
     *         {@code OrderNotFoundException} (non-retryable), {@code PaymentGatewayException} or
     *         {@code InventoryConflictException} (retryable)
     */
    Mono<OrderData> execute(ProcessOrderPaymentRequest request);
}
