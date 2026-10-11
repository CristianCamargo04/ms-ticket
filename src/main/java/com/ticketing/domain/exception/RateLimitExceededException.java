package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

public final class RateLimitExceededException extends TicketingException {
    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        if (retryAfterSeconds < 0) {
            throw new IllegalArgumentException("retryAfterSeconds must be >= 0");
        }
        super(ErrorCodeEnum.RATE_LIMIT_EXCEEDED, "Rate limit exceeded, retry after " + retryAfterSeconds + " s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
