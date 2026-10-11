package com.ticketing.domain.internal_request;

public record ProcessOrderPaymentRequest(String orderId, String paymentToken, String correlationId) {
}
