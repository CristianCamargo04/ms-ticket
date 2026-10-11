package com.ticketing.client.dto.payment;

import java.math.BigDecimal;

public record ChargeRequestDto(String orderId, String customerId, BigDecimal amount, String currency,
                               String paymentToken) {
    @Override
    public String toString() {
        return "ChargeRequestDto[orderId=" + orderId + ", customerId=" + customerId + ", amount=" + amount
                + ", currency=" + currency + ", paymentToken=***]";
    }
}
