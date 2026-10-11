package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

public final class ReservationExpiredException extends TicketingException {
    public ReservationExpiredException(String orderId) {
        super(ErrorCodeEnum.HOLD_EXPIRED, "Reservation hold expired for order " + orderId);
    }
}
