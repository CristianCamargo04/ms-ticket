package com.ticketing.domain.inventory;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Moves the reserved tickets of an order to payment confirmation ({@code RESERVED -> PENDING_CONFIRMATION}). The
 * persisted condition also requires the hold to be still valid and the order to belong to the confirming customer.
 *
 * @param order                the active, reserved order (holding its tickets)
 * @param confirmationDeadline when the payment must be reconciled at the latest, strictly after {@code now}
 * @param now                  command instant
 * @param actor                the confirming customer (JWT subject)
 */
public record ConfirmInventoryCommand(OrderData order, Instant confirmationDeadline, Instant now, String actor)
        implements InventoryCommand {

    /**
     * Validates the command.
     *
     * @throws IllegalArgumentException when an invariant is violated
     */
    public ConfirmInventoryCommand {
        InventoryCommand.requireHeldTickets(order);
        Objects.requireNonNull(confirmationDeadline, "confirmationDeadline");
        Objects.requireNonNull(now, "now");
        DomainPreconditions.require(confirmationDeadline.isAfter(now), "confirmationDeadline must be after now");
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

    /**
     * The audit reason of a confirmation.
     *
     * @return {@link TransitionReasonEnum#CUSTOMER_CONFIRMATION}
     */
    @Override
    public TransitionReasonEnum reason() {
        return TransitionReasonEnum.CUSTOMER_CONFIRMATION;
    }
}
