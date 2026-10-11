package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

/**
 * A request violates a business validation rule ({@link ErrorCodeEnum#VALIDATION_ERROR}) or carries a malformed
 * idempotency key ({@link ErrorCodeEnum#INVALID_IDEMPOTENCY_KEY}). HTTP 400, not retryable.
 */
public final class DomainValidationException extends TicketingException {

    /**
     * Creates a validation error with an explicit code.
     *
     * @param code    {@link ErrorCodeEnum#VALIDATION_ERROR} or {@link ErrorCodeEnum#INVALID_IDEMPOTENCY_KEY}
     * @param message a client-safe description of the violated rule
     * @throws IllegalArgumentException when another code is given
     */
    public DomainValidationException(ErrorCodeEnum code, String message) {
        if (code != ErrorCodeEnum.VALIDATION_ERROR && code != ErrorCodeEnum.INVALID_IDEMPOTENCY_KEY) {
            throw new IllegalArgumentException("Unsupported validation error code: " + code);
        }
        super(code, message);
    }

    /**
     * Creates a {@link ErrorCodeEnum#VALIDATION_ERROR}.
     *
     * @param message a client-safe description of the violated rule
     */
    public DomainValidationException(String message) {
        super(ErrorCodeEnum.VALIDATION_ERROR, message);
    }
}
