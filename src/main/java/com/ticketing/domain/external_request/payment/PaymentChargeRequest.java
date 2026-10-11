package com.ticketing.domain.external_request.payment;

import java.math.BigDecimal;

public record PaymentChargeRequest(String idempotencyKey, String orderId, String customerId, BigDecimal amount,
                                   String currency, String paymentToken) {
}
