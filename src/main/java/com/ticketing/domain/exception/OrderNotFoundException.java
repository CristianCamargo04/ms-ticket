package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

public final class OrderNotFoundException extends TicketingException {
    public OrderNotFoundException(String orderId) {
        super(ErrorCodeEnum.ORDER_NOT_FOUND, "Order not found: " + orderId);
    }
}
