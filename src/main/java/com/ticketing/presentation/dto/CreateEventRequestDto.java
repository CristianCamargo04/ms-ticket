package com.ticketing.presentation.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public record CreateEventRequestDto(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 120) String venue,
        @NotNull @Future Instant eventDate,
        @Min(1) @Max(100000) int totalCapacity,
        @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal ticketPrice,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {
}
