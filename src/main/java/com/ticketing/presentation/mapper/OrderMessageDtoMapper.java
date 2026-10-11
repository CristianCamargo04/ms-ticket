package com.ticketing.presentation.mapper;

import com.ticketing.domain.internal_request.ProcessOrderPaymentRequest;
import com.ticketing.domain.internal_request.ReserveTicketsRequest;
import com.ticketing.presentation.dto.message.OrderReservationMessageRequestDto;
import com.ticketing.presentation.dto.message.PaymentRequestedMessageRequestDto;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class OrderMessageDtoMapper {
    public ReserveTicketsRequest toReserveTicketsRequest(OrderReservationMessageRequestDto message) {
        Objects.requireNonNull(message, "message");
        return new ReserveTicketsRequest(message.orderId(), message.correlationId());
    }

    public ProcessOrderPaymentRequest toProcessOrderPaymentRequest(PaymentRequestedMessageRequestDto message) {
        Objects.requireNonNull(message, "message");
        return new ProcessOrderPaymentRequest(message.orderId(), message.paymentToken(), message.correlationId());
    }
}
