package com.ticketing.domain.inventory;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Common view of the commands executed atomically by {@code InventoryTransactionPersistence} (one DynamoDB
 * {@code TransactWriteItems} per command). It exposes everything needed to build the ticket updates and the audit
 * records generically: the affected tickets, the transition reason and the {@code from -> to} statuses derived from
 * it through {@link TicketStateMachine}.
 */
public sealed interface InventoryCommand
        permits ReserveInventoryCommand, IssueComplimentaryCommand, ConfirmInventoryCommand, FinalizeSaleCommand,
        ReleaseInventoryCommand {

    /**
     * Maximum tickets per command: a transaction holds at most 100 items and a command writes
     * {@code 2n + 2} of them (event, n tickets, order, n audit records).
     */
    int MAX_TICKETS_PER_TRANSACTION = 49;

    /**
     * The order the command applies to, in the state read before the command.
     *
     * @return the order
     */
    OrderData order();

    /**
     * The tickets the command transitions (between 1 and {@link #MAX_TICKETS_PER_TRANSACTION}).
     *
     * @return the ticket ids
     */
    List<String> ticketIds();

    /**
     * The audit reason of the transition.
     *
     * @return the reason
     */
    TransitionReasonEnum reason();

    /**
     * The instant of the command (conditions, {@code updatedAt}, audit {@code occurredAt}).
     *
     * @return the command instant
     */
    Instant now();

    /**
     * Who triggers the command (customer subject, administrator subject or worker name), recorded in the audit.
     *
     * @return the actor
     */
    String actor();

    /**
     * The status every affected ticket must currently have.
     *
     * @return {@code TicketStateMachine.sourceOf(reason())}
     */
    default TicketStatusEnum fromStatus() {
        return TicketStateMachine.sourceOf(reason());
    }

    /**
     * The status every affected ticket gets.
     *
     * @return {@code TicketStateMachine.targetOf(reason())}
     */
    default TicketStatusEnum toStatus() {
        return TicketStateMachine.targetOf(reason());
    }

    /**
     * Number of affected tickets (the counter delta applied to the event).
     *
     * @return {@code ticketIds().size()}
     */
    default int quantity() {
        return ticketIds().size();
    }

    /**
     * Validates a new ticket selection for an order (used by the reserve and complimentary commands): between 1 and
     * {@link #MAX_TICKETS_PER_TRANSACTION} distinct, non-blank ids, exactly {@code order.quantity()} of them.
     *
     * @param order     the order, never {@code null}
     * @param ticketIds the selected tickets
     * @return an immutable copy of {@code ticketIds}
     * @throws IllegalArgumentException when the selection is invalid
     */
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

    /**
     * Validates that an order already holds the tickets a command transitions (used by the confirm, finalize and
     * release commands).
     *
     * @param order the order, never {@code null}
     * @throws IllegalArgumentException when the order holds no ticket or too many for one transaction
     */
    static void requireHeldTickets(OrderData order) {
        Objects.requireNonNull(order, "order");
        DomainPreconditions.requireBetween(order.ticketIds().size(), 1, MAX_TICKETS_PER_TRANSACTION,
                "order ticketIds size");
    }
}
