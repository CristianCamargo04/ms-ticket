package com.ticketing.enums;

/**
 * Business reason recorded with every ticket transition (audit trail).
 */
public enum TransitionReasonEnum {
    RESERVATION,
    COMPLIMENTARY_ISSUED,
    CUSTOMER_CONFIRMATION,
    PAYMENT_APPROVED,
    PAYMENT_DECLINED,
    HOLD_EXPIRED,
    CONFIRMATION_TIMEOUT,
    CUSTOMER_CANCELLATION
}
