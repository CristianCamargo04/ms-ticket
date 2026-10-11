package com.ticketing.domain.internal_request;

/**
 * Input of the cancel-order use case.
 *
 * @param orderId    order identifier
 * @param customerId authenticated customer (must own the order)
 */
public record CancelOrderRequest(String orderId, String customerId) {
}
