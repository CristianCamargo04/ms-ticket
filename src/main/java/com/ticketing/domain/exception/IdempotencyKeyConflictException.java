package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

public final class IdempotencyKeyConflictException extends TicketingException {
    public IdempotencyKeyConflictException(String idempotencyKey) {
        super(ErrorCodeEnum.IDEMPOTENCY_KEY_REUSED,
                "Idempotency key " + idempotencyKey + " was already used with a different request");
    }
}
