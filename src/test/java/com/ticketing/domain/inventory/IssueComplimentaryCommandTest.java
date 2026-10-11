package com.ticketing.domain.inventory;

import static com.ticketing.domain.inventory.OrderFixtures.NOW;
import static com.ticketing.domain.inventory.OrderFixtures.TICKETS;
import static com.ticketing.domain.inventory.OrderFixtures.queued;
import static com.ticketing.domain.inventory.OrderFixtures.ticketIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.domain.data.OrderData;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.util.List;
import org.junit.jupiter.api.Test;

class IssueComplimentaryCommandTest {

    private static OrderData complimentary() {
        return queued(OrderTypeEnum.COMPLIMENTARY, TICKETS.size());
    }

    @Test
    void shouldDescribeAComplimentaryIssue() {
        OrderData order = complimentary();

        IssueComplimentaryCommand command = new IssueComplimentaryCommand(order, TICKETS, NOW, "order-worker");

        assertThat(command.order()).isSameAs(order);
        assertThat(command.ticketIds()).containsExactlyElementsOf(TICKETS);
        assertThat(command.now()).isEqualTo(NOW);
        assertThat(command.actor()).isEqualTo("order-worker");
        assertThat(command.reason()).isEqualTo(TransitionReasonEnum.COMPLIMENTARY_ISSUED);
        assertThat(command.fromStatus()).isEqualTo(TicketStatusEnum.AVAILABLE);
        assertThat(command.toStatus()).isEqualTo(TicketStatusEnum.COMPLIMENTARY);
        assertThat(command.quantity()).isEqualTo(2);
    }

    @Test
    void shouldRejectStandardOrder() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IssueComplimentaryCommand(queued(OrderTypeEnum.STANDARD, 2), TICKETS, NOW,
                        "order-worker"))
                .withMessage("issueComplimentary requires a COMPLIMENTARY order");
    }

    @Test
    void shouldRejectInvalidSelection() {
        assertThatIllegalArgumentException().isThrownBy(() -> new IssueComplimentaryCommand(
                queued(OrderTypeEnum.COMPLIMENTARY, 50), ticketIds(50), NOW, "order-worker"));
        assertThatIllegalArgumentException().isThrownBy(() -> new IssueComplimentaryCommand(complimentary(),
                List.of("T-000001"), NOW, "order-worker"));
    }

    @Test
    void shouldRejectMissingComponents() {
        assertThatNullPointerException()
                .isThrownBy(() -> new IssueComplimentaryCommand(null, TICKETS, NOW, "order-worker"));
        assertThatNullPointerException()
                .isThrownBy(() -> new IssueComplimentaryCommand(complimentary(), TICKETS, null, "order-worker"));
        assertThatNullPointerException()
                .isThrownBy(() -> new IssueComplimentaryCommand(complimentary(), TICKETS, NOW, null));
    }
}
