package com.ticketing.domain.internal_request;

import java.time.Instant;

public record PaymentRequestedMessage(String orderId, String paymentToken, String correlationId, Instant occurredAt) {
}
