package com.ticketing.client.dto.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Response of the payment gateway for {@code POST /v1/charges} and {@code GET /v1/charges/{idempotencyKey}}.
 * Unknown properties are ignored (tolerant reader), so the gateway may add fields without breaking the client.
 *
 * @param paymentId     gateway payment identifier (present when approved)
 * @param status        {@code APPROVED} or {@code DECLINED}
 * @param declineReason gateway decline reason (present when declined)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChargeResponseDto(String paymentId, String status, String declineReason) {

    /** Status of a captured charge. */
    public static final String STATUS_APPROVED = "APPROVED";

    /** Status of a refused charge. */
    public static final String STATUS_DECLINED = "DECLINED";
}
