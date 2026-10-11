package com.ticketing.domain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderDataTest {
    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);
    private static final Instant DISPATCH_DEADLINE = NOW.plusSeconds(30);
    private static final List<String> TICKETS = List.of("T-000001", "T-000002");

    private static OrderData queued() {
        return OrderData.newQueued("o-1", "e-1", "c-1", OrderTypeEnum.STANDARD, 2, "hash", DISPATCH_DEADLINE, NOW);
    }

    private static OrderData withStatuses(OrderProcessingStatusEnum processingStatus, TicketStatusEnum ticketStatus) {
        return new OrderData("o-1", "e-1", "c-1", OrderTypeEnum.STANDARD, 2, List.of(), ticketStatus,
                processingStatus, null, "hash", null, null, null, 1, NOW, NOW);
    }

    private static OrderData withHold(Instant holdExpiresAt) {
        return new OrderData("o-1", "e-1", "c-1", OrderTypeEnum.STANDARD, 2, TICKETS, TicketStatusEnum.RESERVED,
                OrderProcessingStatusEnum.ACTIVE, null, "hash", holdExpiresAt, holdExpiresAt, null, 2, NOW, NOW);
    }

    @Test
    void shouldCreateQueuedOrder() {
        OrderData order = queued();

        assertThat(order.orderId()).isEqualTo("o-1");
        assertThat(order.eventId()).isEqualTo("e-1");
        assertThat(order.customerId()).isEqualTo("c-1");
        assertThat(order.type()).isEqualTo(OrderTypeEnum.STANDARD);
        assertThat(order.quantity()).isEqualTo(2);
        assertThat(order.ticketIds()).isEmpty();
        assertThat(order.ticketStatus()).isEqualTo(TicketStatusEnum.AVAILABLE);
        assertThat(order.processingStatus()).isEqualTo(OrderProcessingStatusEnum.QUEUED);
        assertThat(order.reason()).isNull();
        assertThat(order.requestHash()).isEqualTo("hash");
        assertThat(order.holdExpiresAt()).isNull();
        assertThat(order.deadlineAt()).isEqualTo(DISPATCH_DEADLINE);
        assertThat(order.paymentReference()).isNull();
        assertThat(order.version()).isEqualTo(OrderData.INITIAL_VERSION);
        assertThat(order.createdAt()).isEqualTo(NOW);
        assertThat(order.updatedAt()).isEqualTo(NOW);
        assertThat(order.deadlineType()).isEqualTo(DeadlineTypeEnum.DISPATCH);
    }

    @Test
    void shouldCopyTicketIdsDefensively() {
        List<String> mutable = new ArrayList<>(TICKETS);

        OrderData order = withHold(LATER).withReservation(mutable, LATER, NOW);
        mutable.clear();

        assertThat(order.ticketIds()).containsExactlyElementsOf(TICKETS);
        assertThatThrownBy(() -> order.ticketIds().add("T-000003")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectTicketIdsNotMatchingQuantity() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> queued().withReservation(List.of("T-000001"), LATER, NOW))
                .withMessage("ticketIds must be empty or contain exactly quantity ids");
        assertThatNullPointerException().isThrownBy(() -> queued().withReservation(null, LATER, NOW));
    }

    @Test
    void shouldRejectInvalidComponents() {
        assertThatIllegalArgumentException().isThrownBy(() -> OrderData.newQueued(" ", "e-1", "c-1",
                OrderTypeEnum.STANDARD, 1, "hash", DISPATCH_DEADLINE, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> OrderData.newQueued("o-1", "", "c-1",
                OrderTypeEnum.STANDARD, 1, "hash", DISPATCH_DEADLINE, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> OrderData.newQueued("o-1", "e-1", " ",
                OrderTypeEnum.STANDARD, 1, "hash", DISPATCH_DEADLINE, NOW));
        assertThatNullPointerException().isThrownBy(() -> OrderData.newQueued("o-1", "e-1", "c-1",
                null, 1, "hash", DISPATCH_DEADLINE, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> OrderData.newQueued("o-1", "e-1", "c-1",
                OrderTypeEnum.STANDARD, 0, "hash", DISPATCH_DEADLINE, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> OrderData.newQueued("o-1", "e-1", "c-1",
                OrderTypeEnum.STANDARD, 1, " ", DISPATCH_DEADLINE, NOW));
        assertThatNullPointerException().isThrownBy(() -> OrderData.newQueued("o-1", "e-1", "c-1",
                OrderTypeEnum.STANDARD, 1, "hash", DISPATCH_DEADLINE, null));
        assertThatNullPointerException().isThrownBy(() -> withStatuses(null, TicketStatusEnum.AVAILABLE));
        assertThatNullPointerException().isThrownBy(() -> withStatuses(OrderProcessingStatusEnum.QUEUED, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderData("o-1", "e-1", "c-1",
                OrderTypeEnum.STANDARD, 1, List.of(), TicketStatusEnum.AVAILABLE, OrderProcessingStatusEnum.QUEUED,
                null, "hash", null, null, null, -1, NOW, NOW));
        assertThatNullPointerException().isThrownBy(() -> new OrderData("o-1", "e-1", "c-1",
                OrderTypeEnum.STANDARD, 1, List.of(), TicketStatusEnum.AVAILABLE, OrderProcessingStatusEnum.QUEUED,
                null, "hash", null, null, null, 1, null, NOW));
    }

    @Test
    void shouldRecognizeOwner() {
        OrderData order = queued();

        assertThat(order.isOwnedBy("c-1")).isTrue();
        assertThat(order.isOwnedBy("c-2")).isFalse();
        assertThat(order.isOwnedBy(null)).isFalse();
    }

    @Test
    void shouldConsiderHoldExpiredFromItsExpiryInstant() {
        OrderData order = withHold(LATER);

        assertThat(order.isHoldExpired(LATER.minusMillis(1))).isFalse();
        assertThat(order.isHoldExpired(LATER)).isTrue();
        assertThat(order.isHoldExpired(LATER.plusMillis(1))).isTrue();
    }

    @Test
    void shouldNeverBeHoldExpiredWithoutHold() {
        assertThat(queued().isHoldExpired(LATER)).isFalse();
        assertThatNullPointerException().isThrownBy(() -> queued().isHoldExpired(null));
    }

    @Test
    void shouldMatchOnlyTheOriginalRequestHash() {
        OrderData order = queued();

        assertThat(order.matchesRequest("hash")).isTrue();
        assertThat(order.matchesRequest("other")).isFalse();
        assertThat(order.matchesRequest(null)).isFalse();
    }

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @CsvSource({
            "QUEUED,           AVAILABLE,            DISPATCH",
            "ACTIVE,           RESERVED,             HOLD_EXPIRATION",
            "ACTIVE,           PENDING_CONFIRMATION, PAYMENT_CONFIRMATION",
            "ACTIVE,           AVAILABLE,",
            "ACTIVE,           SOLD,",
            "ACTIVE,           COMPLIMENTARY,",
            "COMPLETED,        SOLD,",
            "REJECTED,         AVAILABLE,",
            "EXPIRED,          AVAILABLE,",
            "CANCELLED,        AVAILABLE,",
            "PAYMENT_DECLINED, AVAILABLE,",
            "FAILED,           AVAILABLE,"
    })
    void shouldDeriveDeadlineTypeFromStatuses(OrderProcessingStatusEnum processingStatus,
                                              TicketStatusEnum ticketStatus, DeadlineTypeEnum expected) {
        assertThat(withStatuses(processingStatus, ticketStatus).deadlineType()).isEqualTo(expected);
    }

    @Test
    void shouldComputeTotalAmount() {
        assertThat(queued().totalAmount(new BigDecimal("49.90"))).isEqualByComparingTo("99.80");
        assertThatNullPointerException().isThrownBy(() -> queued().totalAmount(null));
    }

    @Test
    void shouldApplyReservation() {
        OrderData reserved = queued().withReservation(TICKETS, LATER, NOW.plusSeconds(1));

        assertThat(reserved.ticketIds()).containsExactlyElementsOf(TICKETS);
        assertThat(reserved.ticketStatus()).isEqualTo(TicketStatusEnum.RESERVED);
        assertThat(reserved.processingStatus()).isEqualTo(OrderProcessingStatusEnum.ACTIVE);
        assertThat(reserved.holdExpiresAt()).isEqualTo(LATER);
        assertThat(reserved.deadlineAt()).isEqualTo(LATER);
        assertThat(reserved.deadlineType()).isEqualTo(DeadlineTypeEnum.HOLD_EXPIRATION);
        assertThat(reserved.version()).isEqualTo(OrderData.INITIAL_VERSION + 1);
        assertThat(reserved.updatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(reserved.createdAt()).isEqualTo(NOW);
        assertThatNullPointerException().isThrownBy(() -> queued().withReservation(TICKETS, null, NOW));
    }

    @Test
    void shouldApplyComplimentaryIssue() {
        OrderData issued = queued().withComplimentaryIssued(TICKETS, LATER);

        assertThat(issued.ticketIds()).containsExactlyElementsOf(TICKETS);
        assertThat(issued.ticketStatus()).isEqualTo(TicketStatusEnum.COMPLIMENTARY);
        assertThat(issued.processingStatus()).isEqualTo(OrderProcessingStatusEnum.COMPLETED);
        assertThat(issued.deadlineAt()).isNull();
        assertThat(issued.deadlineType()).isNull();
        assertThat(issued.version()).isEqualTo(OrderData.INITIAL_VERSION + 1);
    }

    @Test
    void shouldApplyPendingConfirmation() {
        Instant confirmationDeadline = LATER.plusSeconds(300);

        OrderData pending = withHold(LATER).withPendingConfirmation(confirmationDeadline, NOW);

        assertThat(pending.ticketStatus()).isEqualTo(TicketStatusEnum.PENDING_CONFIRMATION);
        assertThat(pending.processingStatus()).isEqualTo(OrderProcessingStatusEnum.ACTIVE);
        assertThat(pending.deadlineAt()).isEqualTo(confirmationDeadline);
        assertThat(pending.deadlineType()).isEqualTo(DeadlineTypeEnum.PAYMENT_CONFIRMATION);
        assertThat(pending.holdExpiresAt()).isEqualTo(LATER);
        assertThat(pending.version()).isEqualTo(3);
        assertThatNullPointerException().isThrownBy(() -> withHold(LATER).withPendingConfirmation(null, NOW));
    }

    @Test
    void shouldApplySale() {
        OrderData sold = withHold(LATER).withPendingConfirmation(LATER, NOW).withSale("pay-1", LATER);

        assertThat(sold.ticketStatus()).isEqualTo(TicketStatusEnum.SOLD);
        assertThat(sold.processingStatus()).isEqualTo(OrderProcessingStatusEnum.COMPLETED);
        assertThat(sold.paymentReference()).isEqualTo("pay-1");
        assertThat(sold.deadlineAt()).isNull();
        assertThat(sold.version()).isEqualTo(4);
        assertThat(sold.updatedAt()).isEqualTo(LATER);
        assertThatNullPointerException().isThrownBy(() -> withHold(LATER).withSale(null, NOW));
    }

    @Test
    void shouldApplyRelease() {
        OrderData released = withHold(LATER)
                .withRelease(OrderProcessingStatusEnum.EXPIRED, "HOLD_EXPIRED", LATER);

        assertThat(released.ticketStatus()).isEqualTo(TicketStatusEnum.AVAILABLE);
        assertThat(released.processingStatus()).isEqualTo(OrderProcessingStatusEnum.EXPIRED);
        assertThat(released.reason()).isEqualTo("HOLD_EXPIRED");
        assertThat(released.deadlineAt()).isNull();
        assertThat(released.ticketIds()).containsExactlyElementsOf(TICKETS);
        assertThat(released.holdExpiresAt()).isEqualTo(LATER);
        assertThat(released.version()).isEqualTo(3);
    }

    @Test
    void shouldClearDeadlineWhenProcessingStatusBecomesTerminal() {
        OrderData rejected = queued().withProcessingStatus(OrderProcessingStatusEnum.REJECTED, "SOLD_OUT", LATER);

        assertThat(rejected.processingStatus()).isEqualTo(OrderProcessingStatusEnum.REJECTED);
        assertThat(rejected.ticketStatus()).isEqualTo(TicketStatusEnum.AVAILABLE);
        assertThat(rejected.reason()).isEqualTo("SOLD_OUT");
        assertThat(rejected.deadlineAt()).isNull();
        assertThat(rejected.version()).isEqualTo(OrderData.INITIAL_VERSION + 1);
    }

    @Test
    void shouldKeepDeadlineWhenProcessingStatusIsNotTerminal() {
        OrderData active = queued().withProcessingStatus(OrderProcessingStatusEnum.ACTIVE, null, LATER);

        assertThat(active.deadlineAt()).isEqualTo(DISPATCH_DEADLINE);
        assertThatNullPointerException().isThrownBy(() -> queued().withProcessingStatus(null, null, LATER));
    }

    @Test
    void shouldApplyGenericStateChange() {
        OrderData failed = withHold(LATER)
                .withState(TicketStatusEnum.AVAILABLE, OrderProcessingStatusEnum.FAILED, LATER);
        OrderData stillActive = withHold(LATER)
                .withState(TicketStatusEnum.PENDING_CONFIRMATION, OrderProcessingStatusEnum.ACTIVE, LATER);

        assertThat(failed.ticketStatus()).isEqualTo(TicketStatusEnum.AVAILABLE);
        assertThat(failed.processingStatus()).isEqualTo(OrderProcessingStatusEnum.FAILED);
        assertThat(failed.deadlineAt()).isNull();
        assertThat(stillActive.ticketStatus()).isEqualTo(TicketStatusEnum.PENDING_CONFIRMATION);
        assertThat(stillActive.deadlineAt()).isEqualTo(LATER);
        assertThat(stillActive.version()).isEqualTo(3);
    }

    @Test
    void shouldRescheduleDeadline() {
        Instant next = LATER.plusSeconds(30);

        OrderData rescheduled = queued().withDeadline(next, LATER);

        assertThat(rescheduled.deadlineAt()).isEqualTo(next);
        assertThat(rescheduled.processingStatus()).isEqualTo(OrderProcessingStatusEnum.QUEUED);
        assertThat(rescheduled.updatedAt()).isEqualTo(LATER);
        assertThat(rescheduled.version()).isEqualTo(OrderData.INITIAL_VERSION + 1);
    }
}
