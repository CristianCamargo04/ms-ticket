package com.ticketing.domain.external_request.payment;

import java.math.BigDecimal;

/**
 * Charge request sent to the payment gateway.
 *
 * @param idempotencyKey gateway idempotency key (the order id, so retries never double-charge)
 * @param orderId        order identifier
 * @param customerId     paying customer
 * @param amount         amount to charge
 * @param currency       ISO-4217 currency code
 * @param paymentToken   tokenized payment method (never a card number)
 */
public record PaymentChargeRequest(String idempotencyKey, String orderId, String customerId, BigDecimal amount,
                                   String currency, String paymentToken) {
}
