package com.ticketing.persistence.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.data.TicketData;
import com.ticketing.domain.data.TicketTransitionData;
import com.ticketing.domain.inventory.ConfirmInventoryCommand;
import com.ticketing.domain.inventory.FinalizeSaleCommand;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import com.ticketing.domain.inventory.IssueComplimentaryCommand;
import com.ticketing.domain.inventory.ReleaseInventoryCommand;
import com.ticketing.domain.inventory.ReserveInventoryCommand;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import com.ticketing.persistence.support.PersistenceTestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every inventory transaction against DynamoDB Local: happy paths (including the parity between the in-memory
 * {@code Applied} order and the persisted one) and every failing condition.
 */
class InventoryTransactionPersistenceImplIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration HOLD = Duration.ofMinutes(10);
    private static final String WORKER = "order-worker";

    private static PersistenceTestContext context;

    @BeforeAll
    static void createTables() {
        context = PersistenceTestContext.create();
    }

    @Test
    void shouldReserveTicketsAtomically() {
        EventData event = context.onSaleEventWithTickets(5);
        OrderData order = context.queuedOrder(event.eventId(), OrderTypeEnum.STANDARD, 2);
        Instant now = PersistenceTestContext.now();
        List<String> ticketIds = List.of("T-000002", "T-000004");

        InventoryWriteOutcome outcome = reserve(order, ticketIds, now);

        assertThat(outcome).isInstanceOf(InventoryWriteOutcome.Applied.class);
        OrderData persisted = context.order(order.orderId());
        assertThat(((InventoryWriteOutcome.Applied) outcome).order()).isEqualTo(persisted);
        assertThat(persisted.processingStatus()).isEqualTo(OrderProcessingStatusEnum.ACTIVE);
        assertThat(persisted.ticketStatus()).isEqualTo(TicketStatusEnum.RESERVED);
        assertThat(persisted.ticketIds()).containsExactlyElementsOf(ticketIds);
        assertThat(persisted.holdExpiresAt()).isEqualTo(now.plus(HOLD));
        assertThat(persisted.deadlineType()).isEqualTo(DeadlineTypeEnum.HOLD_EXPIRATION);
        assertCounters(event, 3, 2, 0, 0, 0);
        assertThat(tickets(event, ticketIds)).allSatisfy(ticket -> {
            assertThat(ticket.status()).isEqualTo(TicketStatusEnum.RESERVED);
            assertThat(ticket.orderId()).isEqualTo(order.orderId());
            assertThat(ticket.holdExpiresAt()).isEqualTo(now.plus(HOLD));
            assertThat(ticket.version()).isEqualTo(2);
        });
        assertThat(available(event)).extracting(TicketData::ticketId)
                .containsExactly("T-000001", "T-000003", "T-000005");
        assertThat(history(event, "T-000002")).singleElement().satisfies(transition -> {
            assertThat(transition.fromStatus()).isEqualTo(TicketStatusEnum.AVAILABLE);
            assertThat(transition.toStatus()).isEqualTo(TicketStatusEnum.RESERVED);
            assertThat(transition.reason()).isEqualTo(TransitionReasonEnum.RESERVATION);
            assertThat(transition.orderId()).isEqualTo(order.orderId());
            assertThat(transition.actor()).isEqualTo(WORKER);
            assertThat(transition.occurredAt()).isEqualTo(now);
        });
        assertThat(context.orders().findDueOrders(DeadlineTypeEnum.HOLD_EXPIRATION, now.plus(HOLD), 100)
                .map(OrderData::orderId).collectList().block(TIMEOUT)).contains(order.orderId());
    }

    @Test
    void shouldReportInsufficientInventoryWhenSoldOut() {
        EventData event = context.onSaleEventWithTickets(1);
        OrderData winner = context.queuedOrder(event.eventId(), OrderTypeEnum.STANDARD, 1);
        assertThat(reserve(winner, List.of("T-000001"), PersistenceTestContext.now()))
                .isInstanceOf(InventoryWriteOutcome.Applied.class);
        OrderData late = context.queuedOrder(event.eventId(), OrderTypeEnum.STANDARD, 1);

        assertThat(reserve(late, List.of("T-000001"), PersistenceTestContext.now()))
                .isInstanceOf(InventoryWriteOutcome.InsufficientInventory.class);
        assertThat(context.order(late.orderId())).isEqualTo(late);
        assertCounters(event, 0, 1, 0, 0, 0);
    }

    @Test
    void shouldReportInsufficientInventoryWhenEventIsNotOnSale() {
        EventData event = context.onSaleEventWithTickets(2);
        context.events().updateStatus(event.eventId(), EventStatusEnum.ON_SALE, EventStatusEnum.CLOSED,
                PersistenceTestContext.now()).block(TIMEOUT);
        OrderData order = context.queuedOrder(event.eventId(), OrderTypeEnum.STANDARD, 1);

        assertThat(reserve(order, List.of("T-000001"), PersistenceTestContext.now()))
                .isInstanceOf(InventoryWriteOutcome.InsufficientInventory.class);
        assertThat(tickets(event, List.of("T-000001"))).singleElement()
                .extracting(TicketData::status).isEqualTo(TicketStatusEnum.AVAILABLE);
    }

    @Test
    void shouldReportStaleTicketSelectionWhenATicketIsNoLongerAvailable() {
        EventData event = context.onSaleEventWithTickets(3);
        OrderData first = context.queuedOrder(event.eventId(), OrderTypeEnum.STANDARD, 1);
        reserve(first, List.of("T-000001"), PersistenceTestContext.now());
        OrderData second = context.queuedOrder(event.eventId(), OrderTypeEnum.STANDARD, 2);

        assertThat(reserve(second, List.of("T-000002", "T-000001"), PersistenceTestContext.now()))
                .isInstanceOf(InventoryWriteOutcome.StaleTicketSelection.class);
        assertThat(context.order(second.orderId())).isEqualTo(second);
        assertThat(tickets(event, List.of("T-000001", "T-000002"))).extracting(TicketData::orderId)
                .containsExactly(first.orderId(), null);
        assertCounters(event, 2, 1, 0, 0, 0);
    }

    @Test
    void shouldReportOrderStateChangedWhenTheOrderWasAlreadyProcessed() {
        EventData event = context.onSaleEventWithTickets(3);
        OrderData order = context.queuedOrder(event.eventId(), OrderTypeEnum.STANDARD, 1);
        reserve(order, List.of("T-000001"), PersistenceTestContext.now());

        assertThat(reserve(order, List.of("T-000002"), PersistenceTestContext.now().plusMillis(1)))
                .isInstanceOf(InventoryWriteOutcome.OrderStateChanged.class);
        assertThat(tickets(event, List.of("T-000002"))).singleElement()
                .extracting(TicketData::status).isEqualTo(TicketStatusEnum.AVAILABLE);
        assertCounters(event, 2, 1, 0, 0, 0);
    }

    @Test
    void shouldIssueComplimentaryTickets() {
        EventData event = context.onSaleEventWithTickets(4);
        OrderData order = context.queuedOrder(event.eventId(), OrderTypeEnum.COMPLIMENTARY, 2);
        Instant now = PersistenceTestContext.now();

        InventoryWriteOutcome outcome = context.inventory().issueComplimentary(
                new IssueComplimentaryCommand(order, List.of("T-000003", "T-000004"), now, "admin-1")).block(TIMEOUT);

        assertThat(outcome).isInstanceOf(InventoryWriteOutcome.Applied.class);
        OrderData persisted = context.order(order.orderId());
        assertThat(((InventoryWriteOutcome.Applied) outcome).order()).isEqualTo(persisted);
        assertThat(persisted.processingStatus()).isEqualTo(OrderProcessingStatusEnum.COMPLETED);
        assertThat(persisted.ticketStatus()).isEqualTo(TicketStatusEnum.COMPLIMENTARY);
        assertThat(persisted.deadlineAt()).isNull();
        assertCounters(event, 2, 0, 0, 0, 2);
        assertThat(tickets(event, List.of("T-000003", "T-000004")))
                .extracting(TicketData::status).containsOnly(TicketStatusEnum.COMPLIMENTARY);
        assertThat(available(event)).extracting(TicketData::ticketId).containsExactly("T-000001", "T-000002");
        assertThat(context.orders().findDueOrders(DeadlineTypeEnum.DISPATCH, now.plus(Duration.ofDays(1)), 1000)
                .map(OrderData::orderId).collectList().block(TIMEOUT)).doesNotContain(order.orderId());
    }

    @Test
    void shouldNotIssueComplimentaryTicketsTwice() {
        EventData event = context.onSaleEventWithTickets(4);
        OrderData order = context.queuedOrder(event.eventId(), OrderTypeEnum.COMPLIMENTARY, 1);
        context.inventory().issueComplimentary(new IssueComplimentaryCommand(order, List.of("T-000001"),
                PersistenceTestContext.now(), "admin-1")).block(TIMEOUT);

        assertThat(context.inventory().issueComplimentary(new IssueComplimentaryCommand(order, List.of("T-000002"),
                PersistenceTestContext.now().plusMillis(1), "admin-1")).block(TIMEOUT))
                .isInstanceOf(InventoryWriteOutcome.OrderStateChanged.class);
        assertCounters(event, 3, 0, 0, 0, 1);
    }

    @Test
    void shouldConfirmAReservedOrder() {
        EventData event = context.onSaleEventWithTickets(3);
        OrderData reserved = reserved(event, List.of("T-000001", "T-000002"), PersistenceTestContext.now());
        Instant now = PersistenceTestContext.now().plusSeconds(1);
        Instant deadline = now.plus(Duration.ofMinutes(5));

        InventoryWriteOutcome outcome = context.inventory().confirm(
                new ConfirmInventoryCommand(reserved, deadline, now, reserved.customerId())).block(TIMEOUT);

        assertThat(outcome).isInstanceOf(InventoryWriteOutcome.Applied.class);
        OrderData persisted = context.order(reserved.orderId());
        assertThat(((InventoryWriteOutcome.Applied) outcome).order()).isEqualTo(persisted);
        assertThat(persisted.ticketStatus()).isEqualTo(TicketStatusEnum.PENDING_CONFIRMATION);
        assertThat(persisted.deadlineType()).isEqualTo(DeadlineTypeEnum.PAYMENT_CONFIRMATION);
        assertThat(persisted.deadlineAt()).isEqualTo(deadline);
        assertCounters(event, 1, 0, 2, 0, 0);
        assertThat(tickets(event, reserved.ticketIds())).extracting(TicketData::status)
                .containsOnly(TicketStatusEnum.PENDING_CONFIRMATION);
        assertThat(context.orders().findDueOrders(DeadlineTypeEnum.PAYMENT_CONFIRMATION, deadline, 1000)
                .map(OrderData::orderId).collectList().block(TIMEOUT)).contains(reserved.orderId());
        assertThat(context.orders().findDueOrders(DeadlineTypeEnum.HOLD_EXPIRATION, now.plus(Duration.ofDays(1)),
                1000).map(OrderData::orderId).collectList().block(TIMEOUT)).doesNotContain(reserved.orderId());
    }

    @Test
    void shouldRejectConfirmationAfterTheHoldExpired() {
        EventData event = context.onSaleEventWithTickets(2);
        Instant reservedAt = PersistenceTestContext.now();
        OrderData reserved = reserved(event, List.of("T-000001"), reservedAt);
        Instant afterExpiry = reservedAt.plus(HOLD).plusSeconds(1);

        assertThat(context.inventory().confirm(new ConfirmInventoryCommand(reserved,
                afterExpiry.plus(Duration.ofMinutes(5)), afterExpiry, reserved.customerId())).block(TIMEOUT))
                .isInstanceOf(InventoryWriteOutcome.OrderStateChanged.class);
        assertThat(context.order(reserved.orderId())).isEqualTo(reserved);
        assertCounters(event, 1, 1, 0, 0, 0);
    }

    @Test
    void shouldRejectConfirmationByAnotherCustomer() {
        EventData event = context.onSaleEventWithTickets(2);
        OrderData reserved = reserved(event, List.of("T-000001"), PersistenceTestContext.now());
        Instant now = PersistenceTestContext.now().plusSeconds(1);

        assertThat(context.inventory().confirm(new ConfirmInventoryCommand(reserved, now.plusSeconds(300), now,
                "intruder")).block(TIMEOUT)).isInstanceOf(InventoryWriteOutcome.OrderStateChanged.class);
        assertCounters(event, 1, 1, 0, 0, 0);
    }

    @Test
    void shouldFinalizeASale() {
        EventData event = context.onSaleEventWithTickets(2);
        OrderData pending = pending(event, List.of("T-000002"));
        Instant now = PersistenceTestContext.now().plusSeconds(2);

        InventoryWriteOutcome outcome = context.inventory().finalizeSale(
                new FinalizeSaleCommand(pending, "pay-123", now, "payment-worker")).block(TIMEOUT);

        assertThat(outcome).isInstanceOf(InventoryWriteOutcome.Applied.class);
        OrderData persisted = context.order(pending.orderId());
        assertThat(((InventoryWriteOutcome.Applied) outcome).order()).isEqualTo(persisted);
        assertThat(persisted.ticketStatus()).isEqualTo(TicketStatusEnum.SOLD);
        assertThat(persisted.processingStatus()).isEqualTo(OrderProcessingStatusEnum.COMPLETED);
        assertThat(persisted.paymentReference()).isEqualTo("pay-123");
        assertThat(persisted.deadlineAt()).isNull();
        assertCounters(event, 1, 0, 0, 1, 0);
        assertThat(tickets(event, List.of("T-000002"))).singleElement().satisfies(ticket -> {
            assertThat(ticket.status()).isEqualTo(TicketStatusEnum.SOLD);
            assertThat(ticket.orderId()).isEqualTo(pending.orderId());
            assertThat(ticket.holdExpiresAt()).isNull();
        });
    }

    @Test
    void shouldNotFinalizeAnOrderThatIsNotPendingConfirmation() {
        EventData event = context.onSaleEventWithTickets(2);
        OrderData reserved = reserved(event, List.of("T-000001"), PersistenceTestContext.now());

        assertThat(context.inventory().finalizeSale(new FinalizeSaleCommand(reserved, "pay-1",
                PersistenceTestContext.now().plusSeconds(1), "payment-worker")).block(TIMEOUT))
                .isInstanceOf(InventoryWriteOutcome.OrderStateChanged.class);
        assertCounters(event, 1, 1, 0, 0, 0);
    }

    @Test
    void shouldReleaseOnCustomerCancellation() {
        EventData event = context.onSaleEventWithTickets(2);
        OrderData reserved = reserved(event, List.of("T-000001"), PersistenceTestContext.now());

        InventoryWriteOutcome outcome = release(reserved, TicketStatusEnum.RESERVED,
                OrderProcessingStatusEnum.CANCELLED, TransitionReasonEnum.CUSTOMER_CANCELLATION,
                PersistenceTestContext.now().plusSeconds(1));

        assertThat(outcome).isInstanceOf(InventoryWriteOutcome.Applied.class);
        OrderData persisted = context.order(reserved.orderId());
        assertThat(((InventoryWriteOutcome.Applied) outcome).order()).isEqualTo(persisted);
        assertThat(persisted.processingStatus()).isEqualTo(OrderProcessingStatusEnum.CANCELLED);
        assertThat(persisted.reason()).isEqualTo("CUSTOMER_CANCELLATION");
        assertThat(persisted.deadlineAt()).isNull();
        assertCounters(event, 2, 0, 0, 0, 0);
        assertThat(tickets(event, List.of("T-000001"))).singleElement().satisfies(ticket -> {
            assertThat(ticket.status()).isEqualTo(TicketStatusEnum.AVAILABLE);
            assertThat(ticket.orderId()).isNull();
            assertThat(ticket.holdExpiresAt()).isNull();
        });
        assertThat(available(event)).extracting(TicketData::ticketId).containsExactly("T-000001", "T-000002");
    }

    @Test
    void shouldNotReleaseAnExpiredHoldBeforeItsExpiry() {
        EventData event = context.onSaleEventWithTickets(2);
        Instant reservedAt = PersistenceTestContext.now();
        OrderData reserved = reserved(event, List.of("T-000001"), reservedAt);

        assertThat(release(reserved, TicketStatusEnum.RESERVED, OrderProcessingStatusEnum.EXPIRED,
                TransitionReasonEnum.HOLD_EXPIRED, reservedAt.plus(HOLD).minusMillis(1)))
                .isInstanceOf(InventoryWriteOutcome.OrderStateChanged.class);
        assertCounters(event, 1, 1, 0, 0, 0);

        InventoryWriteOutcome atExpiry = release(reserved, TicketStatusEnum.RESERVED,
                OrderProcessingStatusEnum.EXPIRED, TransitionReasonEnum.HOLD_EXPIRED, reservedAt.plus(HOLD));

        assertThat(atExpiry).isInstanceOf(InventoryWriteOutcome.Applied.class);
        assertThat(context.order(reserved.orderId()).processingStatus()).isEqualTo(OrderProcessingStatusEnum.EXPIRED);
        assertCounters(event, 2, 0, 0, 0, 0);
    }

    @Test
    void shouldNotReleaseOnConfirmationTimeoutBeforeTheDeadline() {
        EventData event = context.onSaleEventWithTickets(2);
        OrderData pending = pending(event, List.of("T-000001"));

        assertThat(release(pending, TicketStatusEnum.PENDING_CONFIRMATION, OrderProcessingStatusEnum.EXPIRED,
                TransitionReasonEnum.CONFIRMATION_TIMEOUT, pending.deadlineAt().minusMillis(1)))
                .isInstanceOf(InventoryWriteOutcome.OrderStateChanged.class);
        assertThat(release(pending, TicketStatusEnum.PENDING_CONFIRMATION, OrderProcessingStatusEnum.EXPIRED,
                TransitionReasonEnum.CONFIRMATION_TIMEOUT, pending.deadlineAt()))
                .isInstanceOf(InventoryWriteOutcome.Applied.class);
        assertCounters(event, 2, 0, 0, 0, 0);
    }

    @Test
    void shouldReleaseOnPaymentDeclined() {
        EventData event = context.onSaleEventWithTickets(2);
        OrderData pending = pending(event, List.of("T-000001", "T-000002"));

        InventoryWriteOutcome outcome = release(pending, TicketStatusEnum.PENDING_CONFIRMATION,
                OrderProcessingStatusEnum.PAYMENT_DECLINED, TransitionReasonEnum.PAYMENT_DECLINED,
                PersistenceTestContext.now().plusSeconds(3));

        assertThat(outcome).isInstanceOf(InventoryWriteOutcome.Applied.class);
        assertThat(((InventoryWriteOutcome.Applied) outcome).order()).isEqualTo(context.order(pending.orderId()));
        assertCounters(event, 2, 0, 0, 0, 0);
        assertThat(available(event)).hasSize(2);
    }

    @Test
    void shouldNotReleaseTicketsTheOrderDoesNotHold() {
        EventData event = context.onSaleEventWithTickets(2);
        OrderData reserved = reserved(event, List.of("T-000001"), PersistenceTestContext.now());

        assertThat(release(reserved, TicketStatusEnum.PENDING_CONFIRMATION, OrderProcessingStatusEnum.EXPIRED,
                TransitionReasonEnum.CONFIRMATION_TIMEOUT, PersistenceTestContext.now().plus(Duration.ofDays(1))))
                .isInstanceOf(InventoryWriteOutcome.OrderStateChanged.class);
        assertCounters(event, 1, 1, 0, 0, 0);
    }

    private static InventoryWriteOutcome reserve(OrderData order, List<String> ticketIds, Instant now) {
        return context.inventory().reserve(new ReserveInventoryCommand(order, ticketIds, now.plus(HOLD), now, WORKER))
                .block(TIMEOUT);
    }

    private static InventoryWriteOutcome release(OrderData order, TicketStatusEnum expected,
                                                 OrderProcessingStatusEnum resulting, TransitionReasonEnum reason,
                                                 Instant now) {
        return context.inventory().release(new ReleaseInventoryCommand(order, expected, resulting, reason, now,
                "sweeper")).block(TIMEOUT);
    }

    private static OrderData reserved(EventData event, List<String> ticketIds, Instant now) {
        OrderData order = context.queuedOrder(event.eventId(), OrderTypeEnum.STANDARD, ticketIds.size());
        assertThat(reserve(order, ticketIds, now)).isInstanceOf(InventoryWriteOutcome.Applied.class);
        return context.order(order.orderId());
    }

    private static OrderData pending(EventData event, List<String> ticketIds) {
        OrderData reserved = reserved(event, ticketIds, PersistenceTestContext.now());
        Instant now = PersistenceTestContext.now().plusSeconds(1);
        assertThat(context.inventory().confirm(new ConfirmInventoryCommand(reserved, now.plus(Duration.ofMinutes(5)),
                now, reserved.customerId())).block(TIMEOUT)).isInstanceOf(InventoryWriteOutcome.Applied.class);
        return context.order(reserved.orderId());
    }

    private static List<TicketData> tickets(EventData event, List<String> ticketIds) {
        return context.tickets().findByIds(event.eventId(), ticketIds).collectList().block(TIMEOUT);
    }

    private static List<TicketData> available(EventData event) {
        return context.tickets().findAvailable(event.eventId(), "", 100).collectList().block(TIMEOUT);
    }

    private static List<TicketTransitionData> history(EventData event, String ticketId) {
        return context.transitions().findByTicket(event.eventId(), ticketId).collectList().block(TIMEOUT);
    }

    private static void assertCounters(EventData event, int available, int reserved, int pending, int sold,
                                       int complimentary) {
        EventData current = context.event(event.eventId());
        assertThat(List.of(current.availableCount(), current.reservedCount(), current.pendingCount(),
                current.soldCount(), current.complimentaryCount()))
                .as("available, reserved, pending, sold, complimentary")
                .containsExactly(available, reserved, pending, sold, complimentary);
    }
}
