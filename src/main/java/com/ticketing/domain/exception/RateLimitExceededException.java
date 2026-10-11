package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

/**
 * The caller exceeded its request quota ({@link ErrorCodeEnum#RATE_LIMIT_EXCEEDED}, HTTP 429 with
 * {@code Retry-After}).
 */
public final class RateLimitExceededException extends TicketingException {

    private final long retryAfterSeconds;

    /**
     * Creates the exception.
     *
     * @param retryAfterSeconds seconds the caller should wait before retrying, {@code >= 0}
     * @throws IllegalArgumentException when negative
     */
    public RateLimitExceededException(long retryAfterSeconds) {
        if (retryAfterSeconds < 0) {
            throw new IllegalArgumentException("retryAfterSeconds must be >= 0");
        }
        super(ErrorCodeEnum.RATE_LIMIT_EXCEEDED, "Rate limit exceeded, retry after " + retryAfterSeconds + " s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * Value of the {@code Retry-After} header.
     *
     * @return seconds to wait before retrying
     */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
