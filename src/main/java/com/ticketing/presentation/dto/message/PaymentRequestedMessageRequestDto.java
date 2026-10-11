package com.ticketing.presentation.dto.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * JSON body of an order-payment SQS message (schema
 * {@code templates/messages/payment-requested-message.schema.json}).
 *
 * @param orderId       order identifier
 * @param paymentToken  tokenized payment method
 * @param correlationId correlation id of the original request
 * @param occurredAt    when the message was produced
 */
public record PaymentRequestedMessageRequestDto(
        @NotBlank @Size(max = 64) String orderId,
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String paymentToken,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{1,64}$") String correlationId,
        @NotNull Instant occurredAt) {
}
