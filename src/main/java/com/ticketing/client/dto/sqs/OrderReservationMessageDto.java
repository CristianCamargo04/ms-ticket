package com.ticketing.client.dto.sqs;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/**
 * JSON body published to the order-reservation queue (schema
 * {@code templates/messages/order-reservation-message.schema.json}, version 1).
 *
 * @param orderId       order identifier
 * @param eventId       event identifier
 * @param correlationId correlation id of the original request
 * @param occurredAt    when the message was produced, always written as an ISO-8601 string
 */
public record OrderReservationMessageDto(
        String orderId,
        String eventId,
        String correlationId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt) {
}
