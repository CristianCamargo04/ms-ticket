package com.ticketing.domain.inventory;

import static com.ticketing.domain.inventory.OrderFixtures.NOW;
import static com.ticketing.domain.inventory.OrderFixtures.TICKETS;
import static com.ticketing.domain.inventory.OrderFixtures.pendingConfirmation;
import static com.ticketing.domain.inventory.OrderFixtures.queuedStandard;
import static com.ticketing.domain.inventory.OrderFixtures.reserved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.domain.data.OrderData;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class ReleaseInventoryCommandTest {
    private static OrderData holding(TicketStatusEnum status) {
        return status == TicketStatusEnum.RESERVED ? reserved() : pendingConfirmation();
    }

    @ParameterizedTest(name = "{0} + {2} -> {1}")
    @CsvSource({
            "RESERVED,             EXPIRED,          HOLD_EXPIRED",
            "RESERVED,             CANCELLED,        CUSTOMER_CANCELLATION",
            "PENDING_CONFIRMATION, PAYMENT_DECLINED, PAYMENT_DECLINED",
            "PENDING_CONFIRMATION, EXPIRED,          CONFIRMATION_TIMEOUT"
    })
    void shouldDescribeEveryReleaseOfTheBusinessFlows(TicketStatusEnum expected, OrderProcessingStatusEnum resulting,
                                                      TransitionReasonEnum reason) {
        OrderData order = holding(expected);

        ReleaseInventoryCommand command = new ReleaseInventoryCommand(order, expected, resulting, reason, NOW,
                "sweeper");

        assertThat(command.order()).isSameAs(order);
        assertThat(command.expectedStatus()).isEqualTo(expected);
        assertThat(command.resultingStatus()).isEqualTo(resulting);
        assertThat(command.reason()).isEqualTo(reason);
        assertThat(command.now()).isEqualTo(NOW);
        assertThat(command.actor()).isEqualTo("sweeper");
        assertThat(command.ticketIds()).containsExactlyElementsOf(TICKETS);
        assertThat(command.fromStatus()).isEqualTo(expected);
        assertThat(command.toStatus()).isEqualTo(TicketStatusEnum.AVAILABLE);
        assertThat(command.quantity()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatusEnum.class, names = {"AVAILABLE", "SOLD", "COMPLIMENTARY"})
    void shouldRejectExpectedStatusThatIsNotHeld(TicketStatusEnum expected) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReleaseInventoryCommand(reserved(), expected, OrderProcessingStatusEnum.EXPIRED,
                        TransitionReasonEnum.HOLD_EXPIRED, NOW, "sweeper"))
                .withMessage("expectedStatus must be RESERVED or PENDING_CONFIRMATION");
    }

    @ParameterizedTest
    @EnumSource(value = OrderProcessingStatusEnum.class, names = {"QUEUED", "ACTIVE"})
    void shouldRejectNonTerminalResultingStatus(OrderProcessingStatusEnum resulting) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReleaseInventoryCommand(reserved(), TicketStatusEnum.RESERVED, resulting,
                        TransitionReasonEnum.HOLD_EXPIRED, NOW, "sweeper"))
                .withMessage("resultingStatus must be terminal");
    }

    @ParameterizedTest
    @EnumSource(value = TransitionReasonEnum.class,
            names = {"RESERVATION", "COMPLIMENTARY_ISSUED", "CUSTOMER_CONFIRMATION", "PAYMENT_APPROVED"})
    void shouldRejectReasonThatDoesNotRelease(TransitionReasonEnum reason) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReleaseInventoryCommand(reserved(), TicketStatusEnum.RESERVED,
                        OrderProcessingStatusEnum.CANCELLED, reason, NOW, "sweeper"))
                .withMessage("reason must release the tickets back to AVAILABLE");
    }

    @Test
    void shouldRejectReasonNotApplicableToExpectedStatus() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReleaseInventoryCommand(pendingConfirmation(),
                        TicketStatusEnum.PENDING_CONFIRMATION, OrderProcessingStatusEnum.EXPIRED,
                        TransitionReasonEnum.HOLD_EXPIRED, NOW, "sweeper"))
                .withMessage("reason does not apply to tickets in expectedStatus");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReleaseInventoryCommand(reserved(), TicketStatusEnum.RESERVED,
                        OrderProcessingStatusEnum.PAYMENT_DECLINED, TransitionReasonEnum.PAYMENT_DECLINED, NOW,
                        "sweeper"));
    }

    @Test
    void shouldRejectOrderWithoutTickets() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReleaseInventoryCommand(queuedStandard(), TicketStatusEnum.RESERVED,
                        OrderProcessingStatusEnum.CANCELLED, TransitionReasonEnum.CUSTOMER_CANCELLATION, NOW, "c-1"));
    }

    @Test
    void shouldRejectMissingComponents() {
        assertThatNullPointerException().isThrownBy(() -> new ReleaseInventoryCommand(null,
                TicketStatusEnum.RESERVED, OrderProcessingStatusEnum.CANCELLED,
                TransitionReasonEnum.CUSTOMER_CANCELLATION, NOW, "c-1"));
        assertThatNullPointerException().isThrownBy(() -> new ReleaseInventoryCommand(reserved(), null,
                OrderProcessingStatusEnum.CANCELLED, TransitionReasonEnum.CUSTOMER_CANCELLATION, NOW, "c-1"));
        assertThatNullPointerException().isThrownBy(() -> new ReleaseInventoryCommand(reserved(),
                TicketStatusEnum.RESERVED, null, TransitionReasonEnum.CUSTOMER_CANCELLATION, NOW, "c-1"));
        assertThatNullPointerException().isThrownBy(() -> new ReleaseInventoryCommand(reserved(),
                TicketStatusEnum.RESERVED, OrderProcessingStatusEnum.CANCELLED, null, NOW, "c-1"));
        assertThatNullPointerException().isThrownBy(() -> new ReleaseInventoryCommand(reserved(),
                TicketStatusEnum.RESERVED, OrderProcessingStatusEnum.CANCELLED,
                TransitionReasonEnum.CUSTOMER_CANCELLATION, null, "c-1"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReleaseInventoryCommand(reserved(),
                TicketStatusEnum.RESERVED, OrderProcessingStatusEnum.CANCELLED,
                TransitionReasonEnum.CUSTOMER_CANCELLATION, NOW, " "));
    }
}
