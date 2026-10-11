package com.ticketing.enums;

public enum OrderProcessingStatusEnum {
    QUEUED,
    ACTIVE,
    COMPLETED,
    REJECTED,
    EXPIRED,
    CANCELLED,
    PAYMENT_DECLINED,
    FAILED;

    public boolean isTerminal() {
        return switch (this) {
            case QUEUED, ACTIVE -> false;
            case COMPLETED, REJECTED, EXPIRED, CANCELLED, PAYMENT_DECLINED, FAILED -> true;
        };
    }
}
