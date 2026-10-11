package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

/**
 * The requested operation is not allowed in the current state of the order
 * ({@link ErrorCodeEnum#INVALID_ORDER_STATE}, HTTP 409).
 */
public final class InvalidOrderStateException extends TicketingException {

    /**
     * Creates the exception.
     *
     * @param orderId the order identifier
     * @param detail  client-safe description of the conflict
     */
    public InvalidOrderStateException(String orderId, String detail) {
        super(ErrorCodeEnum.INVALID_ORDER_STATE, "Order " + orderId + " is in an invalid state: " + detail);
    }
}
