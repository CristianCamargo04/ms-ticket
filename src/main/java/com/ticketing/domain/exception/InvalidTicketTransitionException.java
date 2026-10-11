package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;
import com.ticketing.enums.TicketStatusEnum;

/**
 * A ticket transition outside the state machine was attempted ({@link ErrorCodeEnum#INVALID_TICKET_TRANSITION},
 * HTTP 409).
 */
public final class InvalidTicketTransitionException extends TicketingException {

    private final TicketStatusEnum from;
    private final TicketStatusEnum to;

    /**
     * Creates the exception.
     *
     * @param from current status
     * @param to   requested status
     */
    public InvalidTicketTransitionException(TicketStatusEnum from, TicketStatusEnum to) {
        super(ErrorCodeEnum.INVALID_TICKET_TRANSITION, "Invalid ticket transition from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    /**
     * The status the ticket was in.
     *
     * @return the source status
     */
    public TicketStatusEnum from() {
        return from;
    }

    /**
     * The status that was requested.
     *
     * @return the target status
     */
    public TicketStatusEnum to() {
        return to;
    }
}
