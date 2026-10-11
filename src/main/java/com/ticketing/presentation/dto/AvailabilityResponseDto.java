package com.ticketing.presentation.dto;

import java.time.Instant;

public record AvailabilityResponseDto(String eventId, int totalCapacity, int available, int reserved,
                                      int pendingConfirmation, int sold, int complimentary, Instant capturedAt) {
}
