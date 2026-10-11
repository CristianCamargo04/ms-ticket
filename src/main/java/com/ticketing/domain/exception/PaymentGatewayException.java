package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

public final class PaymentGatewayException extends TicketingException {
    public PaymentGatewayException(String message) {
        super(ErrorCodeEnum.PAYMENT_GATEWAY_UNAVAILABLE, message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(ErrorCodeEnum.PAYMENT_GATEWAY_UNAVAILABLE, message, cause);
    }
}
