package com.ticketing.presentation.mapper;

import com.ticketing.domain.internal_request.ProcessOrderPaymentRequest;
import com.ticketing.domain.internal_request.ReserveTicketsRequest;
import com.ticketing.presentation.dto.message.OrderReservationMessageRequestDto;
import com.ticketing.presentation.dto.message.PaymentRequestedMessageRequestDto;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Maps the SQS message bodies to the worker use-case requests.
 */
@Component
public class OrderMessageDtoMapper {

    /**
     * Maps an order-reservation message.
     *
     * @param message the validated message body
     * @return the reserve-tickets request
     */
    public ReserveTicketsRequest toReserveTicketsRequest(OrderReservationMessageRequestDto message) {
        Objects.requireNonNull(message, "message");
        return new ReserveTicketsRequest(message.orderId(), message.correlationId());
    }

    /**
     * Maps a payment-requested message.
     *
     * @param message the validated message body
     * @return the process-order-payment request
     */
    public ProcessOrderPaymentRequest toProcessOrderPaymentRequest(PaymentRequestedMessageRequestDto message) {
        Objects.requireNonNull(message, "message");
        return new ProcessOrderPaymentRequest(message.orderId(), message.paymentToken(), message.correlationId());
    }
}
