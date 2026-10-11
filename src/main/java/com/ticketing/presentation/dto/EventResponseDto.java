package com.ticketing.presentation.dto;

import com.ticketing.enums.EventStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Public representation of an event.
 *
 * @param eventId          event identifier
 * @param name             display name
 * @param venue            venue name
 * @param eventDate        date of the event
 * @param totalCapacity    total tickets
 * @param ticketPrice      unit price
 * @param currency         ISO-4217 currency code
 * @param status           lifecycle status
 * @param availableTickets tickets still available (may be slightly stale: cached)
 * @param createdAt        creation instant
 */
public record EventResponseDto(String eventId, String name, String venue, Instant eventDate, int totalCapacity,
                               BigDecimal ticketPrice, String currency, EventStatusEnum status,
                               int availableTickets, Instant createdAt) {
}
