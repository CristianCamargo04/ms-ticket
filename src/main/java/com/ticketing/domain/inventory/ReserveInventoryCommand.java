package com.ticketing.domain.inventory;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Reserves the selected tickets for a queued standard order ({@code AVAILABLE -> RESERVED}).
 *
 * @param order         the queued {@link OrderTypeEnum#STANDARD} order
 * @param ticketIds     the selected tickets: {@code order.quantity()} distinct ids, at most
 *                      {@link InventoryCommand#MAX_TICKETS_PER_TRANSACTION}
 * @param holdExpiresAt end of the hold, strictly after {@code now}
 * @param now           command instant
 * @param actor         who triggers the reservation (e.g. {@code order-worker})
 */
public record ReserveInventoryCommand(OrderData order, List<String> ticketIds, Instant holdExpiresAt, Instant now,
                                      String actor) implements InventoryCommand {

    /**
     * Validates the command; {@code ticketIds} is defensively copied.
     *
     * @throws IllegalArgumentException when an invariant is violated
     */
    public ReserveInventoryCommand {
        ticketIds = InventoryCommand.requireValidSelection(order, ticketIds);
        DomainPreconditions.require(order.type() == OrderTypeEnum.STANDARD, "reserve requires a STANDARD order");
        Objects.requireNonNull(holdExpiresAt, "holdExpiresAt");
        Objects.requireNonNull(now, "now");
        DomainPreconditions.require(holdExpiresAt.isAfter(now), "holdExpiresAt must be after now");
        DomainPreconditions.requireNonBlank(actor, "actor");
    }

    /**
     * The audit reason of a reservation.
     *
     * @return {@link TransitionReasonEnum#RESERVATION}
     */
    @Override
    public TransitionReasonEnum reason() {
        return TransitionReasonEnum.RESERVATION;
    }
}
