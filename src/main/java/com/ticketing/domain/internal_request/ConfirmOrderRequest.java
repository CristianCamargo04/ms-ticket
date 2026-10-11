package com.ticketing.domain.internal_request;

public record ConfirmOrderRequest(String orderId, String customerId, String paymentToken, String correlationId) {
}
