package com.ticketing.domain.exception;

import com.ticketing.enums.ErrorCodeEnum;

public final class InventoryConflictException extends TicketingException {
    public InventoryConflictException(String message) {
        super(ErrorCodeEnum.INVENTORY_CONFLICT, message);
    }

    public InventoryConflictException(String message, Throwable cause) {
        super(ErrorCodeEnum.INVENTORY_CONFLICT, message, cause);
    }
}
