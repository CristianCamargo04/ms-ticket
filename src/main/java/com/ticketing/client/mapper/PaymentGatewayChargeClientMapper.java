package com.ticketing.client.mapper;

import com.ticketing.client.dto.payment.ChargeRequestDto;
import com.ticketing.client.dto.payment.ChargeResponseDto;
import com.ticketing.domain.exception.PaymentGatewayException;
import com.ticketing.domain.external_request.payment.PaymentChargeRequest;
import com.ticketing.domain.order.PaymentOutcome;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Translates between the domain payment contract and the payment gateway wire DTOs of {@code client.dto.payment}.
 */
@Component
public class PaymentGatewayChargeClientMapper {

    /**
     * Body of a charge request; the idempotency key is sent as a header, not in the body.
     *
     * @param request the domain charge request
     * @return the wire DTO
     */
    public ChargeRequestDto toChargeRequest(PaymentChargeRequest request) {
        Objects.requireNonNull(request, "request");
        return new ChargeRequestDto(request.orderId(), request.customerId(), request.amount(), request.currency(),
                request.paymentToken());
    }

    /**
     * Outcome of a charge: {@code APPROVED} becomes {@link PaymentOutcome.Approved} (payment id as reference) and
     * {@code DECLINED} becomes {@link PaymentOutcome.Declined}.
     *
     * @param response the gateway response
     * @return the domain outcome
     * @throws PaymentGatewayException when the status is missing or unknown (the raw value is not echoed)
     */
    public PaymentOutcome toOutcome(ChargeResponseDto response) {
        Objects.requireNonNull(response, "response");
        return switch (response.status()) {
            case ChargeResponseDto.STATUS_APPROVED -> new PaymentOutcome.Approved(response.paymentId());
            case ChargeResponseDto.STATUS_DECLINED -> new PaymentOutcome.Declined(response.declineReason());
            case null, default ->
                    throw new PaymentGatewayException("Payment gateway returned an unknown charge status");
        };
    }
}
