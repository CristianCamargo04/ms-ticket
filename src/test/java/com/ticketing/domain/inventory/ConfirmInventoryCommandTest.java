package com.ticketing.domain.inventory;

import static com.ticketing.domain.inventory.OrderFixtures.NOW;
import static com.ticketing.domain.inventory.OrderFixtures.TICKETS;
import static com.ticketing.domain.inventory.OrderFixtures.queuedStandard;
import static com.ticketing.domain.inventory.OrderFixtures.reserved;
import static com.ticketing.domain.inventory.OrderFixtures.reservedWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.domain.data.OrderData;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConfirmInventoryCommandTest {

    private static final Instant DEADLINE = NOW.plusSeconds(300);

    @Test
    void shouldDescribeAConfirmationOfTheHeldTickets() {
        OrderData order = reserved();

        ConfirmInventoryCommand command = new ConfirmInventoryCommand(order, DEADLINE, NOW, "c-1");

        assertThat(command.order()).isSameAs(order);
        assertThat(command.confirmationDeadline()).isEqualTo(DEADLINE);
        assertThat(command.now()).isEqualTo(NOW);
        assertThat(command.actor()).isEqualTo("c-1");
        assertThat(command.ticketIds()).containsExactlyElementsOf(TICKETS);
        assertThat(command.reason()).isEqualTo(TransitionReasonEnum.CUSTOMER_CONFIRMATION);
        assertThat(command.fromStatus()).isEqualTo(TicketStatusEnum.RESERVED);
        assertThat(command.toStatus()).isEqualTo(TicketStatusEnum.PENDING_CONFIRMATION);
        assertThat(command.quantity()).isEqualTo(2);
    }

    @Test
    void shouldRejectOrderWithoutTickets() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConfirmInventoryCommand(queuedStandard(), DEADLINE, NOW, "c-1"))
                .withMessage("order ticketIds size must be between 1 and 49 but was 0");
    }

    @Test
    void shouldRejectOrderHoldingMoreTicketsThanATransactionHolds() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConfirmInventoryCommand(reservedWith(50), DEADLINE, NOW, "c-1"));
    }

    @Test
    void shouldRejectDeadlineNotInTheFuture() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConfirmInventoryCommand(reserved(), NOW, NOW, "c-1"))
                .withMessage("confirmationDeadline must be after now");
    }

    @Test
    void shouldRejectMissingComponents() {
        assertThatNullPointerException().isThrownBy(() -> new ConfirmInventoryCommand(null, DEADLINE, NOW, "c-1"));
        assertThatNullPointerException().isThrownBy(() -> new ConfirmInventoryCommand(reserved(), null, NOW, "c-1"));
        assertThatNullPointerException()
                .isThrownBy(() -> new ConfirmInventoryCommand(reserved(), DEADLINE, null, "c-1"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConfirmInventoryCommand(reserved(), DEADLINE, NOW, ""));
    }
}
