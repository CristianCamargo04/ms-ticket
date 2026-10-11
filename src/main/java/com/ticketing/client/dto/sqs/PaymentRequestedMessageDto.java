package com.ticketing.client.dto.sqs;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/**
 * JSON body published to the order-payment queue (schema
 * {@code templates/messages/payment-requested-message.schema.json}, version 1).
 *
 * <p>The payment token is an opaque gateway token; {@link #toString()} masks it so that the DTO can never leak it
 * into a log line.
 *
 * @param orderId       order identifier
 * @param paymentToken  tokenized payment method
 * @param correlationId correlation id of the original request
 * @param occurredAt    when the message was produced, always written as an ISO-8601 string
 */
public record PaymentRequestedMessageDto(
        String orderId,
        String paymentToken,
        String correlationId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt) {

    /**
     * Describes the message without the payment token.
     *
     * @return a log-safe representation
     */
    @Override
    public String toString() {
        return "PaymentRequestedMessageDto[orderId=" + orderId + ", paymentToken=***, correlationId=" + correlationId
                + ", occurredAt=" + occurredAt + "]";
    }
}
