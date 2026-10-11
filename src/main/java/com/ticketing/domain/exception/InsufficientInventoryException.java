package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

/**
 * Not enough tickets are available ({@link ErrorCodeEnum#SOLD_OUT}, HTTP 409).
 */
public final class InsufficientInventoryException extends TicketingException {

    /**
     * Creates the exception.
     *
     * @param eventId   the event identifier
     * @param requested tickets requested
     * @param available tickets available when the check was made
     */
    public InsufficientInventoryException(String eventId, int requested, int available) {
        super(ErrorCodeEnum.SOLD_OUT, "Not enough tickets for event " + eventId + ": requested " + requested
                + ", available " + available);
    }
}
