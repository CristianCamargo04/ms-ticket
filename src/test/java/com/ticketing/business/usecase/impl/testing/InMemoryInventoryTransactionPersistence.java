package com.ticketing.business.usecase.impl.testing;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.data.TicketData;
import com.ticketing.domain.data.TicketTransitionData;
import com.ticketing.domain.inventory.ConfirmInventoryCommand;
import com.ticketing.domain.inventory.FinalizeSaleCommand;
import com.ticketing.domain.inventory.InventoryCommand;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import com.ticketing.domain.inventory.IssueComplimentaryCommand;
import com.ticketing.domain.inventory.ReleaseInventoryCommand;
import com.ticketing.domain.inventory.ReserveInventoryCommand;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import com.ticketing.persistence.InventoryTransactionPersistence;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import reactor.core.publisher.Mono;

public final class InMemoryInventoryTransactionPersistence implements InventoryTransactionPersistence {
    private final InMemoryTicketingStore store;
    private final AtomicLong transitionSequence = new AtomicLong();
    private final AtomicInteger applied = new AtomicInteger();
    private final AtomicInteger orderStateChanged = new AtomicInteger();
    private final AtomicInteger insufficientInventory = new AtomicInteger();
    private final AtomicInteger staleTicketSelections = new AtomicInteger();

    public InMemoryInventoryTransactionPersistence(InMemoryTicketingStore store) {
        this.store = store;
    }

    @Override
    public Mono<InventoryWriteOutcome> reserve(ReserveInventoryCommand command) {
        int q = command.quantity();
        return store.atomically(() -> transact(command,
                order -> order.processingStatus() == OrderProcessingStatusEnum.QUEUED,
                event -> event.status() == EventStatusEnum.ON_SALE && event.availableCount() >= q,
                false,
                event -> event.withCounters(event.availableCount() - q, event.reservedCount() + q,
                        event.pendingCount(), event.soldCount(), event.complimentaryCount(), command.now()),
                ticket -> ticket.withState(TicketStatusEnum.RESERVED, command.order().orderId(),
                        command.holdExpiresAt(), command.now()),
                order -> order.withReservation(command.ticketIds(), command.holdExpiresAt(), command.now())));
    }

    @Override
    public Mono<InventoryWriteOutcome> issueComplimentary(IssueComplimentaryCommand command) {
        int q = command.quantity();
        return store.atomically(() -> transact(command,
                order -> order.processingStatus() == OrderProcessingStatusEnum.QUEUED,
                event -> event.status() == EventStatusEnum.ON_SALE && event.availableCount() >= q,
                false,
                event -> event.withCounters(event.availableCount() - q, event.reservedCount(), event.pendingCount(),
                        event.soldCount(), event.complimentaryCount() + q, command.now()),
                ticket -> ticket.withState(TicketStatusEnum.COMPLIMENTARY, command.order().orderId(), null,
                        command.now()),
                order -> order.withComplimentaryIssued(command.ticketIds(), command.now())));
    }

    @Override
    public Mono<InventoryWriteOutcome> confirm(ConfirmInventoryCommand command) {
        int q = command.quantity();
        return store.atomically(() -> transact(command,
                order -> order.ticketStatus() == TicketStatusEnum.RESERVED
                        && order.processingStatus() == OrderProcessingStatusEnum.ACTIVE
                        && order.holdExpiresAt() != null && order.holdExpiresAt().isAfter(command.now())
                        && order.customerId().equals(command.actor()),
                Objects::nonNull,
                true,
                event -> event.withCounters(event.availableCount(), event.reservedCount() - q,
                        event.pendingCount() + q, event.soldCount(), event.complimentaryCount(), command.now()),
                ticket -> ticket.withState(TicketStatusEnum.PENDING_CONFIRMATION, ticket.orderId(),
                        ticket.holdExpiresAt(), command.now()),
                order -> order.withPendingConfirmation(command.confirmationDeadline(), command.now())));
    }

    @Override
    public Mono<InventoryWriteOutcome> finalizeSale(FinalizeSaleCommand command) {
        int q = command.quantity();
        return store.atomically(() -> transact(command,
                order -> order.ticketStatus() == TicketStatusEnum.PENDING_CONFIRMATION
                        && order.processingStatus() == OrderProcessingStatusEnum.ACTIVE,
                Objects::nonNull,
                true,
                event -> event.withCounters(event.availableCount(), event.reservedCount(), event.pendingCount() - q,
                        event.soldCount() + q, event.complimentaryCount(), command.now()),
                ticket -> ticket.withState(TicketStatusEnum.SOLD, ticket.orderId(), null, command.now()),
                order -> order.withSale(command.paymentReference(), command.now())));
    }

    @Override
    public Mono<InventoryWriteOutcome> release(ReleaseInventoryCommand command) {
        int q = command.quantity();
        boolean fromReserved = command.expectedStatus() == TicketStatusEnum.RESERVED;
        return store.atomically(() -> transact(command,
                order -> order.ticketStatus() == command.expectedStatus()
                        && order.processingStatus() == OrderProcessingStatusEnum.ACTIVE
                        && (command.reason() != TransitionReasonEnum.HOLD_EXPIRED
                        || order.holdExpiresAt() != null && !order.holdExpiresAt().isAfter(command.now()))
                        && (command.reason() != TransitionReasonEnum.CONFIRMATION_TIMEOUT
                        || order.deadlineAt() != null && !order.deadlineAt().isAfter(command.now())),
                Objects::nonNull,
                true,
                event -> event.withCounters(event.availableCount() + q,
                        fromReserved ? event.reservedCount() - q : event.reservedCount(),
                        fromReserved ? event.pendingCount() : event.pendingCount() - q, event.soldCount(),
                        event.complimentaryCount(), command.now()),
                ticket -> ticket.withState(TicketStatusEnum.AVAILABLE, null, null, command.now()),
                order -> order.withRelease(command.resultingStatus(), command.reason().name(), command.now())));
    }

    public int appliedCount() {
        return applied.get();
    }

    public int orderStateChangedCount() {
        return orderStateChanged.get();
    }

    public int insufficientInventoryCount() {
        return insufficientInventory.get();
    }

    public int staleTicketSelectionCount() {
        return staleTicketSelections.get();
    }

    private InventoryWriteOutcome transact(InventoryCommand command, Predicate<OrderData> orderCondition,
                                           Predicate<EventData> eventCondition, boolean ticketsHeldByOrder,
                                           UnaryOperator<EventData> eventUpdate, UnaryOperator<TicketData> ticketUpdate,
                                           UnaryOperator<OrderData> orderUpdate) {
        String orderId = command.order().orderId();
        String eventId = command.order().eventId();
        Map<String, OrderData> orders = store.ordersTable();
        Map<String, EventData> events = store.eventsTable();
        NavigableMap<String, TicketData> tickets = store.ticketsOf(eventId);

        OrderData order = orders.get(orderId);
        if (order == null || !orderCondition.test(order)) {
            orderStateChanged.incrementAndGet();
            return new InventoryWriteOutcome.OrderStateChanged();
        }
        EventData event = events.get(eventId);
        if (event == null || !eventCondition.test(event)) {
            insufficientInventory.incrementAndGet();
            return new InventoryWriteOutcome.InsufficientInventory();
        }
        for (String ticketId : command.ticketIds()) {
            TicketData ticket = tickets.get(ticketId);
            if (ticket == null || ticket.status() != command.fromStatus()
                    || ticketsHeldByOrder && !orderId.equals(ticket.orderId())) {
                staleTicketSelections.incrementAndGet();
                return new InventoryWriteOutcome.StaleTicketSelection();
            }
        }

        events.put(eventId, eventUpdate.apply(event));
        for (String ticketId : command.ticketIds()) {
            tickets.put(ticketId, ticketUpdate.apply(tickets.get(ticketId)));
            store.transitionsTable().add(new TicketTransitionData(eventId, ticketId,
                    "tr-" + transitionSequence.incrementAndGet(), command.fromStatus(), command.toStatus(), orderId,
                    command.reason(), command.actor(), command.now()));
        }
        OrderData updated = orderUpdate.apply(order);
        orders.put(orderId, updated);
        applied.incrementAndGet();
        return new InventoryWriteOutcome.Applied(updated);
    }
}
