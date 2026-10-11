package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;
import com.ticketing.enums.TicketStatusEnum;

public final class InvalidTicketTransitionException extends TicketingException {
    private final TicketStatusEnum from;
    private final TicketStatusEnum to;

    public InvalidTicketTransitionException(TicketStatusEnum from, TicketStatusEnum to) {
        super(ErrorCodeEnum.INVALID_TICKET_TRANSITION, "Invalid ticket transition from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public TicketStatusEnum from() {
        return from;
    }

    public TicketStatusEnum to() {
        return to;
    }
}
