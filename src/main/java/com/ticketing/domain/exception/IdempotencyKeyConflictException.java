package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

/**
 * The idempotency key was already used with a different payload ({@link ErrorCodeEnum#IDEMPOTENCY_KEY_REUSED},
 * HTTP 422).
 */
public final class IdempotencyKeyConflictException extends TicketingException {

    /**
     * Creates the exception.
     *
     * @param idempotencyKey the reused key (client-provided value)
     */
    public IdempotencyKeyConflictException(String idempotencyKey) {
        super(ErrorCodeEnum.IDEMPOTENCY_KEY_REUSED,
                "Idempotency key " + idempotencyKey + " was already used with a different request");
    }
}
