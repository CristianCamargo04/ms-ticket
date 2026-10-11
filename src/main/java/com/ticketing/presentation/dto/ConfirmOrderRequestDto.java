package com.ticketing.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/orders/{orderId}/confirmation}.
 *
 * @param paymentToken tokenized payment method (never a card number)
 */
public record ConfirmOrderRequestDto(
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String paymentToken) {
}
