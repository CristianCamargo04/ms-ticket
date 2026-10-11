package com.ticketing.enums;

/**
 * Processing status of an order, orthogonal to the status of its tickets.
 */
public enum OrderProcessingStatusEnum {
    QUEUED,
    ACTIVE,
    COMPLETED,
    REJECTED,
    EXPIRED,
    CANCELLED,
    PAYMENT_DECLINED,
    FAILED;

    /**
     * Terminal orders never change again and carry no pending deadline.
     *
     * @return {@code true} for every status except {@link #QUEUED} and {@link #ACTIVE}
     */
    public boolean isTerminal() {
        return switch (this) {
            case QUEUED, ACTIVE -> false;
            case COMPLETED, REJECTED, EXPIRED, CANCELLED, PAYMENT_DECLINED, FAILED -> true;
        };
    }
}
