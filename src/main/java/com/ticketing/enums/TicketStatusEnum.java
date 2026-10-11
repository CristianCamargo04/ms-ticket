package com.ticketing.enums;

import java.util.Objects;

/**
 * Lifecycle status of a single ticket.
 *
 * <p>Allowed transitions (anything else is invalid, including {@code X -> X}):
 * <ul>
 *     <li>{@code AVAILABLE -> RESERVED}</li>
 *     <li>{@code AVAILABLE -> COMPLIMENTARY}</li>
 *     <li>{@code RESERVED -> PENDING_CONFIRMATION}</li>
 *     <li>{@code RESERVED -> AVAILABLE}</li>
 *     <li>{@code PENDING_CONFIRMATION -> SOLD}</li>
 *     <li>{@code PENDING_CONFIRMATION -> AVAILABLE}</li>
 * </ul>
 */
public enum TicketStatusEnum {
    AVAILABLE,
    RESERVED,
    PENDING_CONFIRMATION,
    SOLD,
    COMPLIMENTARY;

    /**
     * Tells whether a ticket in this status may move to {@code target}.
     *
     * @param target the desired status, never {@code null}
     * @return {@code true} when the transition is part of the state machine
     * @throws NullPointerException when {@code target} is {@code null}
     */
    public boolean canTransitionTo(TicketStatusEnum target) {
        Objects.requireNonNull(target, "target");
        return switch (this) {
            case AVAILABLE -> target == RESERVED || target == COMPLIMENTARY;
            case RESERVED -> target == PENDING_CONFIRMATION || target == AVAILABLE;
            case PENDING_CONFIRMATION -> target == SOLD || target == AVAILABLE;
            case SOLD, COMPLIMENTARY -> false;
        };
    }

    /**
     * Terminal statuses never change again.
     *
     * @return {@code true} for {@link #SOLD} and {@link #COMPLIMENTARY}
     */
    public boolean isFinal() {
        return switch (this) {
            case SOLD, COMPLIMENTARY -> true;
            case AVAILABLE, RESERVED, PENDING_CONFIRMATION -> false;
        };
    }

    /**
     * Held tickets are temporarily assigned to an order but not sold yet.
     *
     * @return {@code true} for {@link #RESERVED} and {@link #PENDING_CONFIRMATION}
     */
    public boolean isHeld() {
        return switch (this) {
            case RESERVED, PENDING_CONFIRMATION -> true;
            case AVAILABLE, SOLD, COMPLIMENTARY -> false;
        };
    }

    /**
     * Only sold tickets count for accounting: complimentary tickets are final but never accounted as sales.
     *
     * @return {@code true} only for {@link #SOLD}
     */
    public boolean isAccountable() {
        return this == SOLD;
    }
}
