package com.ticketing.domain.inventory;

import static com.ticketing.domain.inventory.OrderFixtures.NOW;
import static com.ticketing.domain.inventory.OrderFixtures.TICKETS;
import static com.ticketing.domain.inventory.OrderFixtures.pendingConfirmation;
import static com.ticketing.domain.inventory.OrderFixtures.queuedStandard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.domain.data.OrderData;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import org.junit.jupiter.api.Test;

class FinalizeSaleCommandTest {

    @Test
    void shouldDescribeASaleOfTheHeldTickets() {
        OrderData order = pendingConfirmation();

        FinalizeSaleCommand command = new FinalizeSaleCommand(order, "pay-1", NOW, "payment-worker");

        assertThat(command.order()).isSameAs(order);
        assertThat(command.paymentReference()).isEqualTo("pay-1");
        assertThat(command.now()).isEqualTo(NOW);
        assertThat(command.actor()).isEqualTo("payment-worker");
        assertThat(command.ticketIds()).containsExactlyElementsOf(TICKETS);
        assertThat(command.reason()).isEqualTo(TransitionReasonEnum.PAYMENT_APPROVED);
        assertThat(command.fromStatus()).isEqualTo(TicketStatusEnum.PENDING_CONFIRMATION);
        assertThat(command.toStatus()).isEqualTo(TicketStatusEnum.SOLD);
    }

    @Test
    void shouldRejectInvalidComponents() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FinalizeSaleCommand(queuedStandard(), "pay-1", NOW, "payment-worker"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FinalizeSaleCommand(pendingConfirmation(), " ", NOW, "payment-worker"));
        assertThatNullPointerException()
                .isThrownBy(() -> new FinalizeSaleCommand(pendingConfirmation(), null, NOW, "payment-worker"));
        assertThatNullPointerException()
                .isThrownBy(() -> new FinalizeSaleCommand(pendingConfirmation(), "pay-1", null, "payment-worker"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FinalizeSaleCommand(pendingConfirmation(), "pay-1", NOW, " "));
        assertThatNullPointerException()
                .isThrownBy(() -> new FinalizeSaleCommand(null, "pay-1", NOW, "payment-worker"));
    }
}
