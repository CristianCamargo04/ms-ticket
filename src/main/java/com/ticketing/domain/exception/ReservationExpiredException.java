package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

/**
 * The reservation hold of the order is over ({@link ErrorCodeEnum#HOLD_EXPIRED}, HTTP 410).
 */
public final class ReservationExpiredException extends TicketingException {

    /**
     * Creates the exception.
     *
     * @param orderId the order identifier
     */
    public ReservationExpiredException(String orderId) {
        super(ErrorCodeEnum.HOLD_EXPIRED, "Reservation hold expired for order " + orderId);
    }
}
