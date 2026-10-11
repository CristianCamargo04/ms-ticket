package com.ticketing.domain.internal_request;

import java.math.BigDecimal;
import java.time.Instant;

public record CreateEventRequest(String name, String venue, Instant eventDate, int totalCapacity,
                                 BigDecimal ticketPrice, String currency) {
}
