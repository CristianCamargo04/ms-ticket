package com.ticketing.presentation.dto;

import java.time.Instant;

/**
 * Inventory counters of an event (also the payload of the {@code availability} SSE events).
 *
 * @param eventId             event identifier
 * @param totalCapacity       total tickets
 * @param available           tickets that can be reserved
 * @param reserved            tickets held by reservations
 * @param pendingConfirmation tickets awaiting the payment result
 * @param sold                tickets sold
 * @param complimentary       complimentary tickets issued
 * @param capturedAt          when the counters were read
 */
public record AvailabilityResponseDto(String eventId, int totalCapacity, int available, int reserved,
                                      int pendingConfirmation, int sold, int complimentary, Instant capturedAt) {
}
