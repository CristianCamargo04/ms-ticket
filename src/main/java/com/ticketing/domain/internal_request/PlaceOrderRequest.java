package com.ticketing.domain.internal_request;

public record PlaceOrderRequest(String eventId, String customerId, int quantity, String idempotencyKey,
                                String requestHash, String correlationId) {
}
