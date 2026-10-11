package com.ticketing.client.mapper;

import com.ticketing.client.dto.payment.ChargeRequestDto;
import com.ticketing.client.dto.payment.ChargeResponseDto;
import com.ticketing.domain.exception.PaymentGatewayException;
import com.ticketing.domain.external_request.payment.PaymentChargeRequest;
import com.ticketing.domain.order.PaymentOutcome;
import java.util.Objects;
import org.mapstruct.Mapper;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.factory.Mappers;

@Mapper(
        componentModel = "spring",
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface PaymentGatewayChargeClientMapper {

    PaymentGatewayChargeClientMapper INSTANCE = Mappers.getMapper(PaymentGatewayChargeClientMapper.class);

    ChargeRequestDto toChargeRequest(PaymentChargeRequest request);

    default PaymentOutcome toOutcome(ChargeResponseDto response) {
        Objects.requireNonNull(response, "response");
        return switch (response.status()) {
            case ChargeResponseDto.STATUS_APPROVED -> new PaymentOutcome.Approved(response.paymentId());
            case ChargeResponseDto.STATUS_DECLINED -> new PaymentOutcome.Declined(response.declineReason());
            case null, default ->
                    throw new PaymentGatewayException("Payment gateway returned an unknown charge status");
        };
    }
}
