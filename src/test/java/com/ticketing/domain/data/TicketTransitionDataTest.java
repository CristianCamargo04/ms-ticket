package com.ticketing.domain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TicketTransitionDataTest {
    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

    @Test
    void shouldExposeEveryComponent() {
        TicketTransitionData transition = new TicketTransitionData("e-1", "T-000001", "tr-1",
                TicketStatusEnum.AVAILABLE, TicketStatusEnum.RESERVED, "o-1", TransitionReasonEnum.RESERVATION,
                "order-worker", NOW);

        assertThat(transition.eventId()).isEqualTo("e-1");
        assertThat(transition.ticketId()).isEqualTo("T-000001");
        assertThat(transition.transitionId()).isEqualTo("tr-1");
        assertThat(transition.fromStatus()).isEqualTo(TicketStatusEnum.AVAILABLE);
        assertThat(transition.toStatus()).isEqualTo(TicketStatusEnum.RESERVED);
        assertThat(transition.orderId()).isEqualTo("o-1");
        assertThat(transition.reason()).isEqualTo(TransitionReasonEnum.RESERVATION);
        assertThat(transition.actor()).isEqualTo("order-worker");
        assertThat(transition.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void shouldRejectMissingComponents() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TicketTransitionData("e-1", "T-000001", " ",
                TicketStatusEnum.AVAILABLE, TicketStatusEnum.RESERVED, "o-1", TransitionReasonEnum.RESERVATION,
                "order-worker", NOW));
        assertThatNullPointerException().isThrownBy(() -> new TicketTransitionData("e-1", "T-000001", "tr-1",
                null, TicketStatusEnum.RESERVED, "o-1", TransitionReasonEnum.RESERVATION, "order-worker", NOW));
        assertThatNullPointerException().isThrownBy(() -> new TicketTransitionData("e-1", "T-000001", "tr-1",
                TicketStatusEnum.AVAILABLE, TicketStatusEnum.RESERVED, null, TransitionReasonEnum.RESERVATION,
                "order-worker", NOW));
        assertThatNullPointerException().isThrownBy(() -> new TicketTransitionData("e-1", "T-000001", "tr-1",
                TicketStatusEnum.AVAILABLE, TicketStatusEnum.RESERVED, "o-1", null, "order-worker", NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> new TicketTransitionData("e-1", "T-000001", "tr-1",
                TicketStatusEnum.AVAILABLE, TicketStatusEnum.RESERVED, "o-1", TransitionReasonEnum.RESERVATION,
                "", NOW));
        assertThatNullPointerException().isThrownBy(() -> new TicketTransitionData("e-1", "T-000001", "tr-1",
                TicketStatusEnum.AVAILABLE, TicketStatusEnum.RESERVED, "o-1", TransitionReasonEnum.RESERVATION,
                "order-worker", null));
    }
}
