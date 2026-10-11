package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

/**
 * A message could not be published to a queue ({@link ErrorCodeEnum#MESSAGE_PUBLISHING_FAILED}, HTTP 503,
 * retryable).
 */
public final class MessagePublishingException extends TicketingException {

    /**
     * Creates the exception.
     *
     * @param message description of the failure
     * @param cause   the underlying failure
     */
    public MessagePublishingException(String message, Throwable cause) {
        super(ErrorCodeEnum.MESSAGE_PUBLISHING_FAILED, message, cause);
    }
}
