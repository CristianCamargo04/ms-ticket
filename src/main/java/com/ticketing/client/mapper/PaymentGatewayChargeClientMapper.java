package com.ticketing.client.mapper;

import com.ticketing.client.dto.payment.ChargeRequestDto;
import com.ticketing.client.dto.payment.ChargeResponseDto;
import com.ticketing.domain.exception.PaymentGatewayException;
import com.ticketing.domain.external_request.payment.PaymentChargeRequest;
import com.ticketing.domain.order.PaymentOutcome;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PaymentGatewayChargeClientMapper {
    public ChargeRequestDto toChargeRequest(PaymentChargeRequest request) {
        Objects.requireNonNull(request, "request");
        return new ChargeRequestDto(request.orderId(), request.customerId(), request.amount(), request.currency(),
                request.paymentToken());
    }

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
