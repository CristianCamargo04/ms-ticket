package com.ticketing.domain.inventory;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record FinalizeSaleCommand(OrderData order, String paymentReference, Instant now, String actor)
        implements InventoryCommand {
    public FinalizeSaleCommand {
        InventoryCommand.requireHeldTickets(order);
        DomainPreconditions.requireNonBlank(paymentReference, "paymentReference");
        Objects.requireNonNull(now, "now");
        DomainPreconditions.requireNonBlank(actor, "actor");
    }

    @Override
    public List<String> ticketIds() {
        return order.ticketIds();
    }

    @Override
    public TransitionReasonEnum reason() {
        return TransitionReasonEnum.PAYMENT_APPROVED;
    }
}
