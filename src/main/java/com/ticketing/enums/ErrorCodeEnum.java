package com.ticketing.enums;

/**
 * Stable error codes exposed to clients (ProblemDetail {@code errorCode}) with their HTTP status and retry semantics.
 */
public enum ErrorCodeEnum {
    VALIDATION_ERROR(400, false),
    INVALID_IDEMPOTENCY_KEY(400, false),
    UNAUTHORIZED(401, false),
    FORBIDDEN(403, false),
    EVENT_NOT_FOUND(404, false),
    ORDER_NOT_FOUND(404, false),
    EVENT_NOT_ON_SALE(409, false),
    SOLD_OUT(409, false),
    INVALID_ORDER_STATE(409, false),
    INVALID_TICKET_TRANSITION(409, false),
    HOLD_EXPIRED(410, false),
    IDEMPOTENCY_KEY_REUSED(422, false),
    RATE_LIMIT_EXCEEDED(429, false),
    INTERNAL_ERROR(500, false),
    INVENTORY_CONFLICT(503, true),
    MESSAGE_PUBLISHING_FAILED(503, true),
    PAYMENT_GATEWAY_UNAVAILABLE(503, true);

    private final int httpStatus;
    private final boolean retryable;

    ErrorCodeEnum(int httpStatus, boolean retryable) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    /**
     * HTTP status used when this error reaches a REST client.
     *
     * @return the HTTP status code
     */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * Whether the failure is transient and the operation may be retried.
     *
     * @return {@code true} for transient failures
     */
    public boolean retryable() {
        return retryable;
    }
}
