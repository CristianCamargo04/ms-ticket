package com.ticketing.enums;

/**
 * Lifecycle status of an event.
 */
public enum EventStatusEnum {
    /** Tickets are being generated; the event is not sellable yet. */
    PUBLISHING,
    /** Tickets can be ordered. */
    ON_SALE,
    /** Sales are closed. */
    CLOSED
}
