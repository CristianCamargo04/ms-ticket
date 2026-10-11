package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

/**
 * The event does not exist ({@link ErrorCodeEnum#EVENT_NOT_FOUND}, HTTP 404).
 */
public final class EventNotFoundException extends TicketingException {

    /**
     * Creates the exception.
     *
     * @param eventId the missing event identifier
     */
    public EventNotFoundException(String eventId) {
        super(ErrorCodeEnum.EVENT_NOT_FOUND, "Event not found: " + eventId);
    }
}
