package com.ticketing.domain.internal_request;

import java.time.Instant;

/**
 * Message asking the workers to charge a confirmed order (published to the order-payment queue).
 *
 * @param orderId       order identifier
 * @param paymentToken  tokenized payment method
 * @param correlationId correlation id of the original request
 * @param occurredAt    when the message was produced
 */
public record PaymentRequestedMessage(String orderId, String paymentToken, String correlationId, Instant occurredAt) {
}
