package com.ticketing.domain.inventory;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Issues the selected tickets to a queued complimentary order ({@code AVAILABLE -> COMPLIMENTARY}).
 *
 * @param order     the queued {@link OrderTypeEnum#COMPLIMENTARY} order
 * @param ticketIds the selected tickets: {@code order.quantity()} distinct ids, at most
 *                  {@link InventoryCommand#MAX_TICKETS_PER_TRANSACTION}
 * @param now       command instant
 * @param actor     who triggers the issue (e.g. {@code order-worker})
 */
public record IssueComplimentaryCommand(OrderData order, List<String> ticketIds, Instant now, String actor)
        implements InventoryCommand {

    /**
     * Validates the command; {@code ticketIds} is defensively copied.
     *
     * @throws IllegalArgumentException when an invariant is violated
     */
    public IssueComplimentaryCommand {
        ticketIds = InventoryCommand.requireValidSelection(order, ticketIds);
        DomainPreconditions.require(order.type() == OrderTypeEnum.COMPLIMENTARY,
                "issueComplimentary requires a COMPLIMENTARY order");
        Objects.requireNonNull(now, "now");
        DomainPreconditions.requireNonBlank(actor, "actor");
    }

    /**
     * The audit reason of a complimentary issue.
     *
     * @return {@link TransitionReasonEnum#COMPLIMENTARY_ISSUED}
     */
    @Override
    public TransitionReasonEnum reason() {
        return TransitionReasonEnum.COMPLIMENTARY_ISSUED;
    }
}
