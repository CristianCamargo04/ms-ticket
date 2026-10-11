package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;
import com.ticketing.enums.EventStatusEnum;

public final class EventNotOnSaleException extends TicketingException {
    public EventNotOnSaleException(String eventId, EventStatusEnum status) {
        super(ErrorCodeEnum.EVENT_NOT_ON_SALE, "Event " + eventId + " is not on sale (status " + status + ")");
    }
}
