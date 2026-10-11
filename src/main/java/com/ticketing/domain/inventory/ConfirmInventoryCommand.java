package com.ticketing.domain.inventory;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ConfirmInventoryCommand(OrderData order, Instant confirmationDeadline, Instant now, String actor)
        implements InventoryCommand {
    public ConfirmInventoryCommand {
        InventoryCommand.requireHeldTickets(order);
        Objects.requireNonNull(confirmationDeadline, "confirmationDeadline");
        Objects.requireNonNull(now, "now");
        DomainPreconditions.require(confirmationDeadline.isAfter(now), "confirmationDeadline must be after now");
        DomainPreconditions.requireNonBlank(actor, "actor");
    }

    @Override
    public List<String> ticketIds() {
        return order.ticketIds();
    }

    @Override
    public TransitionReasonEnum reason() {
        return TransitionReasonEnum.CUSTOMER_CONFIRMATION;
    }
}
