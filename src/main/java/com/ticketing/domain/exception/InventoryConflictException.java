package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

/**
 * Transient inventory contention: transaction conflict, throttling, stale ticket selection or index lag
 * ({@link ErrorCodeEnum#INVENTORY_CONFLICT}, HTTP 503, retryable).
 */
public final class InventoryConflictException extends TicketingException {

    /**
     * Creates the exception.
     *
     * @param message description of the conflict
     */
    public InventoryConflictException(String message) {
        super(ErrorCodeEnum.INVENTORY_CONFLICT, message);
    }

    /**
     * Creates the exception with its technical cause.
     *
     * @param message description of the conflict
     * @param cause   the underlying failure (e.g. an SDK exception)
     */
    public InventoryConflictException(String message, Throwable cause) {
        super(ErrorCodeEnum.INVENTORY_CONFLICT, message, cause);
    }
}
