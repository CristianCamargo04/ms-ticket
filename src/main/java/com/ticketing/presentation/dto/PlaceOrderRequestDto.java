package com.ticketing.presentation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record PlaceOrderRequestDto(@Min(1) @Max(10) int quantity) {
}
