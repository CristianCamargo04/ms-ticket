package com.ticketing.domain.internal_request;

/**
 * Input of the reserve-tickets use case (built from an order reservation message).
 *
 * @param orderId       order identifier
 * @param correlationId correlation id propagated from the original request
 */
public record ReserveTicketsRequest(String orderId, String correlationId) {
}
