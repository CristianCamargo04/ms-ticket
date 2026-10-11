package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

public final class EventNotFoundException extends TicketingException {
    public EventNotFoundException(String eventId) {
        super(ErrorCodeEnum.EVENT_NOT_FOUND, "Event not found: " + eventId);
    }
}
