package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

public final class InsufficientInventoryException extends TicketingException {
    public InsufficientInventoryException(String eventId, int requested, int available) {
        super(ErrorCodeEnum.SOLD_OUT, "Not enough tickets for event " + eventId + ": requested " + requested
                + ", available " + available);
    }
}
