package com.ticketing.domain.inventory;

import com.ticketing.domain.exception.InvalidTicketTransitionException;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.util.Objects;

/**
 * Ticket state machine: validates transitions and maps every {@link TransitionReasonEnum} to the transition it
 * performs. The transition matrix itself lives in {@link TicketStatusEnum#canTransitionTo(TicketStatusEnum)}.
 *
 * <table>
 *     <caption>Transition performed by each reason</caption>
 *     <tr><th>Reason</th><th>From</th><th>To</th></tr>
 *     <tr><td>RESERVATION</td><td>AVAILABLE</td><td>RESERVED</td></tr>
 *     <tr><td>COMPLIMENTARY_ISSUED</td><td>AVAILABLE</td><td>COMPLIMENTARY</td></tr>
 *     <tr><td>CUSTOMER_CONFIRMATION</td><td>RESERVED</td><td>PENDING_CONFIRMATION</td></tr>
 *     <tr><td>PAYMENT_APPROVED</td><td>PENDING_CONFIRMATION</td><td>SOLD</td></tr>
 *     <tr><td>PAYMENT_DECLINED</td><td>PENDING_CONFIRMATION</td><td>AVAILABLE</td></tr>
 *     <tr><td>HOLD_EXPIRED</td><td>RESERVED</td><td>AVAILABLE</td></tr>
 *     <tr><td>CONFIRMATION_TIMEOUT</td><td>PENDING_CONFIRMATION</td><td>AVAILABLE</td></tr>
 *     <tr><td>CUSTOMER_CANCELLATION</td><td>RESERVED</td><td>AVAILABLE</td></tr>
 * </table>
 */
public final class TicketStateMachine {

    private TicketStateMachine() {
    }

    /**
     * Ensures that {@code from -> to} is an allowed transition.
     *
     * @param from current status
     * @param to   requested status
     * @throws InvalidTicketTransitionException when the transition is not allowed (including {@code X -> X})
     * @throws NullPointerException             when an argument is {@code null}
     */
    public static void ensureTransition(TicketStatusEnum from, TicketStatusEnum to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.canTransitionTo(to)) {
            throw new InvalidTicketTransitionException(from, to);
        }
    }

    /**
     * Status reached by a ticket when a transition happens for {@code reason}.
     *
     * @param reason the transition reason
     * @return the target status
     */
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

    /**
     * Status a ticket must be in for a transition to happen for {@code reason}.
     *
     * @param reason the transition reason
     * @return the expected source status
     */
    public static TicketStatusEnum sourceOf(TransitionReasonEnum reason) {
        return switch (reason) {
            case RESERVATION, COMPLIMENTARY_ISSUED -> TicketStatusEnum.AVAILABLE;
            case CUSTOMER_CONFIRMATION, HOLD_EXPIRED, CUSTOMER_CANCELLATION -> TicketStatusEnum.RESERVED;
            case PAYMENT_APPROVED, PAYMENT_DECLINED, CONFIRMATION_TIMEOUT -> TicketStatusEnum.PENDING_CONFIRMATION;
        };
    }
}
