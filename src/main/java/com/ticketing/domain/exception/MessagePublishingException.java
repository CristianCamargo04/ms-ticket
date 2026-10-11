package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

public final class MessagePublishingException extends TicketingException {
    public MessagePublishingException(String message, Throwable cause) {
        super(ErrorCodeEnum.MESSAGE_PUBLISHING_FAILED, message, cause);
    }
}
