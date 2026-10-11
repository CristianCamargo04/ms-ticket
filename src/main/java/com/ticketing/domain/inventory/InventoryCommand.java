package com.ticketing.domain.inventory;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public sealed interface InventoryCommand
        permits ReserveInventoryCommand, IssueComplimentaryCommand, ConfirmInventoryCommand, FinalizeSaleCommand,
        ReleaseInventoryCommand {
    int MAX_TICKETS_PER_TRANSACTION = 49;

    OrderData order();

    List<String> ticketIds();

    TransitionReasonEnum reason();

    Instant now();

    String actor();

    default TicketStatusEnum fromStatus() {
        return TicketStateMachine.sourceOf(reason());
    }

    default TicketStatusEnum toStatus() {
        return TicketStateMachine.targetOf(reason());
    }

    default int quantity() {
        return ticketIds().size();
    }

    static List<String> requireValidSelection(OrderData order, List<String> ticketIds) {
        Objects.requireNonNull(order, "order");
        List<String> selection = List.copyOf(Objects.requireNonNull(ticketIds, "ticketIds"));
        DomainPreconditions.requireBetween(selection.size(), 1, MAX_TICKETS_PER_TRANSACTION, "ticketIds size");
        DomainPreconditions.require(selection.size() == order.quantity(),
                "ticketIds size must match the order quantity");
        DomainPreconditions.require(Set.copyOf(selection).size() == selection.size(), "ticketIds must be distinct");
        selection.forEach(ticketId -> DomainPreconditions.requireNonBlank(ticketId, "ticketId"));
        return selection;
    }

    static void requireHeldTickets(OrderData order) {
        Objects.requireNonNull(order, "order");
        DomainPreconditions.requireBetween(order.ticketIds().size(), 1, MAX_TICKETS_PER_TRANSACTION,
                "order ticketIds size");
    }
}
