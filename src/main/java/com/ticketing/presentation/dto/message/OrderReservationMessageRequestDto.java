package com.ticketing.presentation.dto.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record OrderReservationMessageRequestDto(
        @NotBlank @Size(max = 64) String orderId,
        @NotBlank @Size(max = 64) String eventId,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{1,64}$") String correlationId,
        @NotNull Instant occurredAt) {
}
