package com.ticketing.domain.inventory;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ReserveInventoryCommand(OrderData order, List<String> ticketIds, Instant holdExpiresAt, Instant now,
                                      String actor) implements InventoryCommand {
    public ReserveInventoryCommand {
        ticketIds = InventoryCommand.requireValidSelection(order, ticketIds);
        DomainPreconditions.require(order.type() == OrderTypeEnum.STANDARD, "reserve requires a STANDARD order");
        Objects.requireNonNull(holdExpiresAt, "holdExpiresAt");
        Objects.requireNonNull(now, "now");
        DomainPreconditions.require(holdExpiresAt.isAfter(now), "holdExpiresAt must be after now");
        DomainPreconditions.requireNonBlank(actor, "actor");
    }

    @Override
    public TransitionReasonEnum reason() {
        return TransitionReasonEnum.RESERVATION;
    }
}
