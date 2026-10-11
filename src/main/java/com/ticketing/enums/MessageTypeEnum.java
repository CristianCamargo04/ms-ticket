package com.ticketing.enums;

/**
 * Type of asynchronous message exchanged through the queues (sent as the {@code messageType} attribute).
 */
public enum MessageTypeEnum {
    ORDER_RESERVATION_REQUESTED,
    PAYMENT_REQUESTED
}
