package com.ticketing.domain.internal_request;

import java.time.Instant;

public record OrderReservationMessage(String orderId, String eventId, String correlationId, Instant occurredAt) {
}
