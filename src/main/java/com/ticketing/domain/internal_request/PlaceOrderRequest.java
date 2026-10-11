package com.ticketing.domain.internal_request;

/**
 * Input of the place-order use case.
 *
 * @param eventId        event identifier
 * @param customerId     authenticated customer (JWT subject, never taken from the body)
 * @param quantity       number of tickets
 * @param idempotencyKey value of the {@code Idempotency-Key} header
 * @param requestHash    fingerprint of the request payload
 * @param correlationId  request correlation id
 */
public record PlaceOrderRequest(String eventId, String customerId, int quantity, String idempotencyKey,
                                String requestHash, String correlationId) {
}
