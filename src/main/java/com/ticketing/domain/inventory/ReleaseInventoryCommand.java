package com.ticketing.domain.inventory;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ReleaseInventoryCommand(OrderData order, TicketStatusEnum expectedStatus,
                                      OrderProcessingStatusEnum resultingStatus, TransitionReasonEnum reason,
                                      Instant now, String actor) implements InventoryCommand {
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

    @Override
    public List<String> ticketIds() {
        return order.ticketIds();
    }
}
