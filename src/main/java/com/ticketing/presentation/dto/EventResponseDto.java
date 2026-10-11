package com.ticketing.presentation.dto;

import com.ticketing.enums.EventStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;

public record EventResponseDto(String eventId, String name, String venue, Instant eventDate, int totalCapacity,
                               BigDecimal ticketPrice, String currency, EventStatusEnum status,
                               int availableTickets, Instant createdAt) {
}
