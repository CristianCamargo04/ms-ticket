package com.ticketing.domain.inventory;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Finalizes the sale of an order whose payment was approved ({@code PENDING_CONFIRMATION -> SOLD}).
 *
 * @param order            the active order pending confirmation (holding its tickets)
 * @param paymentReference the approved payment identifier
 * @param now              command instant
 * @param actor            who finalizes the sale (e.g. {@code payment-worker} or the sweeper)
 */
public record FinalizeSaleCommand(OrderData order, String paymentReference, Instant now, String actor)
        implements InventoryCommand {

    /**
     * Validates the command.
     *
     * @throws IllegalArgumentException when an invariant is violated
     */
    public FinalizeSaleCommand {
        InventoryCommand.requireHeldTickets(order);
        DomainPreconditions.requireNonBlank(paymentReference, "paymentReference");
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

    /**
     * The audit reason of a finalized sale.
     *
     * @return {@link TransitionReasonEnum#PAYMENT_APPROVED}
     */
    @Override
    public TransitionReasonEnum reason() {
        return TransitionReasonEnum.PAYMENT_APPROVED;
    }
}
