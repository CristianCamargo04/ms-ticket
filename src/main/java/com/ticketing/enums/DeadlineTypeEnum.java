package com.ticketing.enums;

/**
 * Future action an order is waiting for; drives the sparse deadline index scanned by the sweepers.
 */
public enum DeadlineTypeEnum {
    /** Order is QUEUED: its reservation message must be republished if it was not processed in time. */
    DISPATCH,
    /** Order is ACTIVE + RESERVED: the hold expires at the deadline. */
    HOLD_EXPIRATION,
    /** Order is ACTIVE + PENDING_CONFIRMATION: the payment must be reconciled at the deadline. */
    PAYMENT_CONFIRMATION
}
