package com.ticketing.domain.internal_request;

/**
 * Input of the confirm-order use case.
 *
 * @param orderId       order identifier
 * @param customerId    authenticated customer (must own the order)
 * @param paymentToken  tokenized payment method
 * @param correlationId request correlation id
 */
public record ConfirmOrderRequest(String orderId, String customerId, String paymentToken, String correlationId) {
}
