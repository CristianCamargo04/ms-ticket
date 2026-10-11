package com.ticketing.domain.inventory;

import static com.ticketing.domain.inventory.OrderFixtures.HOLD_EXPIRES_AT;
import static com.ticketing.domain.inventory.OrderFixtures.NOW;
import static com.ticketing.domain.inventory.OrderFixtures.TICKETS;
import static com.ticketing.domain.inventory.OrderFixtures.queued;
import static com.ticketing.domain.inventory.OrderFixtures.queuedStandard;
import static com.ticketing.domain.inventory.OrderFixtures.ticketIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.domain.data.OrderData;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReserveInventoryCommandTest {

    @Test
    void shouldDescribeAReservation() {
        OrderData order = queuedStandard();

        ReserveInventoryCommand command = new ReserveInventoryCommand(order, TICKETS, HOLD_EXPIRES_AT, NOW,
                "order-worker");

        assertThat(command.order()).isSameAs(order);
        assertThat(command.ticketIds()).containsExactlyElementsOf(TICKETS);
        assertThat(command.holdExpiresAt()).isEqualTo(HOLD_EXPIRES_AT);
        assertThat(command.now()).isEqualTo(NOW);
        assertThat(command.actor()).isEqualTo("order-worker");
        assertThat(command.reason()).isEqualTo(TransitionReasonEnum.RESERVATION);
        assertThat(command.fromStatus()).isEqualTo(TicketStatusEnum.AVAILABLE);
        assertThat(command.toStatus()).isEqualTo(TicketStatusEnum.RESERVED);
        assertThat(command.quantity()).isEqualTo(2);
    }

    @Test
    void shouldCopyTicketIdsDefensively() {
        List<String> selection = new ArrayList<>(TICKETS);

        ReserveInventoryCommand command = new ReserveInventoryCommand(queuedStandard(), selection, HOLD_EXPIRES_AT,
                NOW, "order-worker");
        selection.set(0, "T-999999");

        assertThat(command.ticketIds()).containsExactlyElementsOf(TICKETS);
    }

    @Test
    void shouldAcceptTheTransactionLimit() {
        int limit = InventoryCommand.MAX_TICKETS_PER_TRANSACTION;

        ReserveInventoryCommand command = new ReserveInventoryCommand(queued(OrderTypeEnum.STANDARD, limit),
                ticketIds(limit), HOLD_EXPIRES_AT, NOW, "order-worker");

        assertThat(command.quantity()).isEqualTo(49);
    }

    @Test
    void shouldRejectMoreTicketsThanATransactionHolds() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReserveInventoryCommand(queued(OrderTypeEnum.STANDARD, 50), ticketIds(50),
                        HOLD_EXPIRES_AT, NOW, "order-worker"))
                .withMessage("ticketIds size must be between 1 and 49 but was 50");
    }

    @Test
    void shouldRejectEmptySelection() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ReserveInventoryCommand(
                queued(OrderTypeEnum.STANDARD, 1), List.of(), HOLD_EXPIRES_AT, NOW, "order-worker"));
    }

    @Test
    void shouldRejectSelectionNotMatchingQuantity() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReserveInventoryCommand(queuedStandard(), List.of("T-000001"),
                        HOLD_EXPIRES_AT, NOW, "order-worker"))
                .withMessage("ticketIds size must match the order quantity");
    }

    @Test
    void shouldRejectDuplicateTickets() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReserveInventoryCommand(queuedStandard(), List.of("T-000001", "T-000001"),
                        HOLD_EXPIRES_AT, NOW, "order-worker"))
                .withMessage("ticketIds must be distinct");
    }

    @Test
    void shouldRejectBlankTicketId() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ReserveInventoryCommand(queuedStandard(),
                List.of("T-000001", " "), HOLD_EXPIRES_AT, NOW, "order-worker"));
    }

    @Test
    void shouldRejectComplimentaryOrder() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReserveInventoryCommand(queued(OrderTypeEnum.COMPLIMENTARY, 2), TICKETS,
                        HOLD_EXPIRES_AT, NOW, "order-worker"))
                .withMessage("reserve requires a STANDARD order");
    }

    @Test
    void shouldRejectHoldNotInTheFuture() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReserveInventoryCommand(queuedStandard(), TICKETS, NOW, NOW, "order-worker"))
                .withMessage("holdExpiresAt must be after now");
    }

    @Test
    void shouldRejectMissingComponents() {
        assertThatNullPointerException().isThrownBy(
                () -> new ReserveInventoryCommand(null, TICKETS, HOLD_EXPIRES_AT, NOW, "order-worker"));
        assertThatNullPointerException().isThrownBy(
                () -> new ReserveInventoryCommand(queuedStandard(), null, HOLD_EXPIRES_AT, NOW, "order-worker"));
        assertThatNullPointerException().isThrownBy(
                () -> new ReserveInventoryCommand(queuedStandard(), TICKETS, null, NOW, "order-worker"));
        assertThatNullPointerException().isThrownBy(
                () -> new ReserveInventoryCommand(queuedStandard(), TICKETS, HOLD_EXPIRES_AT, null, "order-worker"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new ReserveInventoryCommand(queuedStandard(), TICKETS, HOLD_EXPIRES_AT, NOW, " "));
    }
}
