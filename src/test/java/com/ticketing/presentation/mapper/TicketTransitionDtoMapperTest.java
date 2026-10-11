package com.ticketing.presentation.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.domain.data.TicketTransitionData;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import com.ticketing.presentation.dto.TicketTransitionResponseDto;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TicketTransitionDtoMapperTest {
    private final TicketTransitionDtoMapper mapper = TicketTransitionDtoMapper.INSTANCE;

    @Test
    void shouldMapATransitionWithoutInternalIdentifiers() {
        Instant occurredAt = Instant.parse("2026-10-10T10:00:00Z");
        TicketTransitionData transition = new TicketTransitionData("e-1", "T-000001", "tr-1",
                TicketStatusEnum.RESERVED, TicketStatusEnum.PENDING_CONFIRMATION, "o-1",
                TransitionReasonEnum.CUSTOMER_CONFIRMATION, "customer-1", occurredAt);

        assertThat(mapper.toResponse(transition)).isEqualTo(new TicketTransitionResponseDto("T-000001",
                TicketStatusEnum.RESERVED, TicketStatusEnum.PENDING_CONFIRMATION, "o-1",
                TransitionReasonEnum.CUSTOMER_CONFIRMATION, "customer-1", occurredAt));
    }

    @Test
    void shouldRejectNull() {
        assertThat(mapper.toResponse(null)).isNull();
    }
}
