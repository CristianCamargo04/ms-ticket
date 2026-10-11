package com.ticketing.enums;

public enum ErrorCodeEnum {
    VALIDATION_ERROR(400, false),
    INVALID_IDEMPOTENCY_KEY(400, false),
    UNAUTHORIZED(401, false),
    FORBIDDEN(403, false),
    EVENT_NOT_FOUND(404, false),
    ORDER_NOT_FOUND(404, false),
    RESOURCE_NOT_FOUND(404, false),
    METHOD_NOT_ALLOWED(405, false),
    NOT_ACCEPTABLE(406, false),
    EVENT_NOT_ON_SALE(409, false),
    SOLD_OUT(409, false),
    INVALID_ORDER_STATE(409, false),
    INVALID_TICKET_TRANSITION(409, false),
    HOLD_EXPIRED(410, false),
    PAYLOAD_TOO_LARGE(413, false),
    UNSUPPORTED_MEDIA_TYPE(415, false),
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

    public int httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }
}
