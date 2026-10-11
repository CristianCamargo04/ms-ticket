package com.ticketing.domain.internal_request;

public record ReserveTicketsRequest(String orderId, String correlationId) {
}
