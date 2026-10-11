package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

public final class DomainValidationException extends TicketingException {
    public DomainValidationException(ErrorCodeEnum code, String message) {
        if (code != ErrorCodeEnum.VALIDATION_ERROR && code != ErrorCodeEnum.INVALID_IDEMPOTENCY_KEY) {
            throw new IllegalArgumentException("Unsupported validation error code: " + code);
        }
        super(code, message);
    }

    public DomainValidationException(String message) {
        super(ErrorCodeEnum.VALIDATION_ERROR, message);
    }
}
