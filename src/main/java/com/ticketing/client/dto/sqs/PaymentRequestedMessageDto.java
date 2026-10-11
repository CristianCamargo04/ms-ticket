package com.ticketing.client.dto.sqs;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public record PaymentRequestedMessageDto(
        String orderId,
        String paymentToken,
        String correlationId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt) {
    @Override
    public String toString() {
        return "PaymentRequestedMessageDto[orderId=" + orderId + ", paymentToken=***, correlationId=" + correlationId
                + ", occurredAt=" + occurredAt + "]";
    }
}
