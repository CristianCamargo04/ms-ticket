package com.ticketing.domain.internal_request;

import java.time.Instant;

/**
 * Message asking the workers to reserve the tickets of a queued order (published to the order-reservation queue).
 *
 * @param orderId       order identifier
 * @param eventId       event identifier
 * @param correlationId correlation id of the original request
 * @param occurredAt    when the message was produced
 */
public record OrderReservationMessage(String orderId, String eventId, String correlationId, Instant occurredAt) {
}
