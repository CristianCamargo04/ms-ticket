package com.ticketing.enums;

import java.util.Objects;

public enum TicketStatusEnum {
    AVAILABLE,
    RESERVED,
    PENDING_CONFIRMATION,
    SOLD,
    COMPLIMENTARY;

    public boolean canTransitionTo(TicketStatusEnum target) {
        Objects.requireNonNull(target, "target");
        return switch (this) {
            case AVAILABLE -> target == RESERVED || target == COMPLIMENTARY;
            case RESERVED -> target == PENDING_CONFIRMATION || target == AVAILABLE;
            case PENDING_CONFIRMATION -> target == SOLD || target == AVAILABLE;
            case SOLD, COMPLIMENTARY -> false;
        };
    }

    public boolean isFinal() {
        return switch (this) {
            case SOLD, COMPLIMENTARY -> true;
            case AVAILABLE, RESERVED, PENDING_CONFIRMATION -> false;
        };
    }

    public boolean isHeld() {
        return switch (this) {
            case RESERVED, PENDING_CONFIRMATION -> true;
            case AVAILABLE, SOLD, COMPLIMENTARY -> false;
        };
    }

    public boolean isAccountable() {
        return this == SOLD;
    }
}
