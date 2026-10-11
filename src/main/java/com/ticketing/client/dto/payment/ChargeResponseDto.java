package com.ticketing.client.dto.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChargeResponseDto(String paymentId, String status, String declineReason) {
    public static final String STATUS_APPROVED = "APPROVED";

    public static final String STATUS_DECLINED = "DECLINED";
}
