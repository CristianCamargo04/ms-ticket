package com.ticketing.presentation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Body of {@code POST /api/v1/events/{eventId}/orders}. The customer comes from the JWT, never from the body.
 *
 * @param quantity number of tickets
 */
public record PlaceOrderRequestDto(@Min(1) @Max(10) int quantity) {
}
