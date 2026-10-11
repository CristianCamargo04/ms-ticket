package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;
import java.util.Objects;

/**
 * Base class of every business exception. Each concrete exception carries a stable {@link ErrorCodeEnum} that
 * determines the HTTP status and whether the failure is transient. Messages are in English and never contain
 * sensitive data (tokens, personal data); identifiers are allowed.
 */
public abstract class TicketingException extends RuntimeException {

    private final ErrorCodeEnum errorCode;

    /**
     * Creates the exception.
     *
     * @param errorCode the error code, never {@code null}
     * @param message   a client-safe message
     */
    protected TicketingException(ErrorCodeEnum errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    /**
     * Creates the exception with a technical cause.
     *
     * @param errorCode the error code, never {@code null}
     * @param message   a client-safe message
     * @param cause     the underlying failure
     */
    protected TicketingException(ErrorCodeEnum errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    /**
     * The stable error code.
     *
     * @return the error code
     */
    public ErrorCodeEnum errorCode() {
        return errorCode;
    }

    /**
     * Whether the failure is transient (delegates to {@link ErrorCodeEnum#retryable()}).
     *
     * @return {@code true} when the operation may be retried
     */
    public boolean retryable() {
        return errorCode.retryable();
    }

    /**
     * Retry filter usable for any throwable, e.g. {@code ReactiveRetryUtil.backoff(..., TicketingException::isRetryable)}.
     *
     * @param error any failure, may be {@code null}
     * @return {@code true} only for a {@link TicketingException} whose error code is retryable
     */
    public static boolean isRetryable(Throwable error) {
        return error instanceof TicketingException ticketingException && ticketingException.retryable();
    }
}
