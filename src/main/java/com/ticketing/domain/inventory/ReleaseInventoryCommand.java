package com.ticketing.domain.inventory;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Releases the held tickets of an order back to the inventory ({@code expectedStatus -> AVAILABLE}) and closes the
 * order with a terminal processing status.
 *
 * <p>Valid combinations (enforced through {@link TicketStateMachine#sourceOf(TransitionReasonEnum)}):
 * {@code RESERVED} with {@code HOLD_EXPIRED} or {@code CUSTOMER_CANCELLATION}; {@code PENDING_CONFIRMATION} with
 * {@code PAYMENT_DECLINED} or {@code CONFIRMATION_TIMEOUT}.
 *
 * @param order           the active order holding its tickets
 * @param expectedStatus  current status of the tickets: {@code RESERVED} or {@code PENDING_CONFIRMATION}
 * @param resultingStatus terminal processing status of the order (e.g. EXPIRED, CANCELLED, PAYMENT_DECLINED)
 * @param reason          release reason; its source status must be {@code expectedStatus} and its target
 *                        {@code AVAILABLE}
 * @param now             command instant
 * @param actor           who triggers the release (customer subject, worker or sweeper name)
 */
public record ReleaseInventoryCommand(OrderData order, TicketStatusEnum expectedStatus,
                                      OrderProcessingStatusEnum resultingStatus, TransitionReasonEnum reason,
                                      Instant now, String actor) implements InventoryCommand {

    /**
     * Validates the command.
     *
     * @throws IllegalArgumentException when an invariant is violated
     */
    public ReleaseInventoryCommand {
        InventoryCommand.requireHeldTickets(order);
        Objects.requireNonNull(expectedStatus, "expectedStatus");
        DomainPreconditions.require(expectedStatus.isHeld(),
                "expectedStatus must be RESERVED or PENDING_CONFIRMATION");
        Objects.requireNonNull(resultingStatus, "resultingStatus");
        DomainPreconditions.require(resultingStatus.isTerminal(), "resultingStatus must be terminal");
        Objects.requireNonNull(reason, "reason");
        DomainPreconditions.require(TicketStateMachine.targetOf(reason) == TicketStatusEnum.AVAILABLE,
                "reason must release the tickets back to AVAILABLE");
        DomainPreconditions.require(TicketStateMachine.sourceOf(reason) == expectedStatus,
                "reason does not apply to tickets in expectedStatus");
        Objects.requireNonNull(now, "now");
        DomainPreconditions.requireNonBlank(actor, "actor");
    }

    /**
     * The tickets held by the order.
     *
     * @return {@code order().ticketIds()}
     */
    @Override
    public List<String> ticketIds() {
        return order.ticketIds();
    }
}
