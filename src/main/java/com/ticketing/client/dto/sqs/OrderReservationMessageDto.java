package com.ticketing.client.dto.sqs;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public record OrderReservationMessageDto(
        String orderId,
        String eventId,
        String correlationId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt) {
}
