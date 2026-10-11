package com.ticketing.domain.inventory;

import com.ticketing.domain.exception.InvalidTicketTransitionException;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.util.Objects;

public final class TicketStateMachine {
    private TicketStateMachine() {
    }

    public static void ensureTransition(TicketStatusEnum from, TicketStatusEnum to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.canTransitionTo(to)) {
            throw new InvalidTicketTransitionException(from, to);
        }
    }

    public static TicketStatusEnum targetOf(TransitionReasonEnum reason) {
        return switch (reason) {
            case RESERVATION -> TicketStatusEnum.RESERVED;
            case COMPLIMENTARY_ISSUED -> TicketStatusEnum.COMPLIMENTARY;
            case CUSTOMER_CONFIRMATION -> TicketStatusEnum.PENDING_CONFIRMATION;
            case PAYMENT_APPROVED -> TicketStatusEnum.SOLD;
            case PAYMENT_DECLINED, HOLD_EXPIRED, CONFIRMATION_TIMEOUT, CUSTOMER_CANCELLATION ->
                    TicketStatusEnum.AVAILABLE;
        };
    }

    public static TicketStatusEnum sourceOf(TransitionReasonEnum reason) {
        return switch (reason) {
            case RESERVATION, COMPLIMENTARY_ISSUED -> TicketStatusEnum.AVAILABLE;
            case CUSTOMER_CONFIRMATION, HOLD_EXPIRED, CUSTOMER_CANCELLATION -> TicketStatusEnum.RESERVED;
            case PAYMENT_APPROVED, PAYMENT_DECLINED, CONFIRMATION_TIMEOUT -> TicketStatusEnum.PENDING_CONFIRMATION;
        };
    }
}
