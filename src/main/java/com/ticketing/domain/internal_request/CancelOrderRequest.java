package com.ticketing.domain.internal_request;

public record CancelOrderRequest(String orderId, String customerId) {
}
