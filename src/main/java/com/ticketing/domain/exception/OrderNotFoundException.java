package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

/**
 * The order does not exist, or the requester does not own it ({@link ErrorCodeEnum#ORDER_NOT_FOUND}, HTTP 404).
 * Using the same error for both cases avoids leaking the existence of other customers' orders (anti-IDOR).
 */
public final class OrderNotFoundException extends TicketingException {

    /**
     * Creates the exception.
     *
     * @param orderId the requested order identifier
     */
    public OrderNotFoundException(String orderId) {
        super(ErrorCodeEnum.ORDER_NOT_FOUND, "Order not found: " + orderId);
    }
}
