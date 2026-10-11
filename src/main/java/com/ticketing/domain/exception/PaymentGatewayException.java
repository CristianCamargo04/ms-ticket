package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

/**
 * The payment gateway is unavailable: timeout, 5xx or open circuit
 * ({@link ErrorCodeEnum#PAYMENT_GATEWAY_UNAVAILABLE}, HTTP 503, retryable).
 */
public final class PaymentGatewayException extends TicketingException {

    /**
     * Creates the exception.
     *
     * @param message description of the failure
     */
    public PaymentGatewayException(String message) {
        super(ErrorCodeEnum.PAYMENT_GATEWAY_UNAVAILABLE, message);
    }

    /**
     * Creates the exception with its technical cause.
     *
     * @param message description of the failure
     * @param cause   the underlying failure
     */
    public PaymentGatewayException(String message, Throwable cause) {
        super(ErrorCodeEnum.PAYMENT_GATEWAY_UNAVAILABLE, message, cause);
    }
}
