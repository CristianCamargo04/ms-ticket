package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;
import com.ticketing.enums.EventStatusEnum;

/**
 * The event exists but does not accept orders ({@link ErrorCodeEnum#EVENT_NOT_ON_SALE}, HTTP 409).
 */
public final class EventNotOnSaleException extends TicketingException {

    /**
     * Creates the exception.
     *
     * @param eventId the event identifier
     * @param status  its current status
     */
    public EventNotOnSaleException(String eventId, EventStatusEnum status) {
        super(ErrorCodeEnum.EVENT_NOT_ON_SALE, "Event " + eventId + " is not on sale (status " + status + ")");
    }
}
