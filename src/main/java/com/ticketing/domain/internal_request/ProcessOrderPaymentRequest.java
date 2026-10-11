package com.ticketing.domain.internal_request;

/**
 * Input of the process-order-payment use case (built from a payment requested message).
 *
 * @param orderId       order identifier
 * @param paymentToken  tokenized payment method
 * @param correlationId correlation id propagated from the original request
 */
public record ProcessOrderPaymentRequest(String orderId, String paymentToken, String correlationId) {
}
