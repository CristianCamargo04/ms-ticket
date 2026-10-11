package com.ticketing.domain.internal_request;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Input of the create-event use case (validated by the use case).
 *
 * @param name          display name
 * @param venue         venue name
 * @param eventDate     when the event takes place
 * @param totalCapacity number of tickets to generate
 * @param ticketPrice   unit price
 * @param currency      ISO-4217 currency code
 */
public record CreateEventRequest(String name, String venue, Instant eventDate, int totalCapacity,
                                 BigDecimal ticketPrice, String currency) {
}
