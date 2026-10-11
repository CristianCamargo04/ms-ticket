package com.ticketing.client.dto.payment;

import java.math.BigDecimal;

/**
 * Body of {@code POST /v1/charges} on the payment gateway. The idempotency key travels in the
 * {@code Idempotency-Key} header, not in the body.
 *
 * <p>{@link #toString()} masks the payment token so that the DTO can never leak it into a log line.
 *
 * @param orderId      order identifier
 * @param customerId   paying customer
 * @param amount       amount to charge
 * @param currency     ISO-4217 currency code
 * @param paymentToken tokenized payment method (never a card number)
 */
public record ChargeRequestDto(String orderId, String customerId, BigDecimal amount, String currency,
                               String paymentToken) {

    /**
     * Describes the charge without the payment token.
     *
     * @return a log-safe representation
     */
    @Override
    public String toString() {
        return "ChargeRequestDto[orderId=" + orderId + ", customerId=" + customerId + ", amount=" + amount
                + ", currency=" + currency + ", paymentToken=***]";
    }
}
