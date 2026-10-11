package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;
import java.util.Objects;

public abstract class TicketingException extends RuntimeException {
    private final ErrorCodeEnum errorCode;

    protected TicketingException(ErrorCodeEnum errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    protected TicketingException(ErrorCodeEnum errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public ErrorCodeEnum errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return errorCode.retryable();
    }

    public static boolean isRetryable(Throwable error) {
        return error instanceof TicketingException ticketingException && ticketingException.retryable();
    }
}
