package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

public final class InvalidOrderStateException extends TicketingException {
    public InvalidOrderStateException(String orderId, String detail) {
        super(ErrorCodeEnum.INVALID_ORDER_STATE, "Order " + orderId + " is in an invalid state: " + detail);
    }
}
