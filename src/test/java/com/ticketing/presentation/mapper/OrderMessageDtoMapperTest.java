package com.ticketing.presentation.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.domain.internal_request.ProcessOrderPaymentRequest;
import com.ticketing.domain.internal_request.ReserveTicketsRequest;
import com.ticketing.presentation.dto.message.OrderReservationMessageRequestDto;
import com.ticketing.presentation.dto.message.PaymentRequestedMessageRequestDto;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderMessageDtoMapperTest {
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-10T10:00:00Z");

    private final OrderMessageDtoMapper mapper = OrderMessageDtoMapper.INSTANCE;

    @Test
    void shouldMapReservationMessages() {
        assertThat(mapper.toReserveTicketsRequest(
                new OrderReservationMessageRequestDto("o-1", "e-1", "corr-1", OCCURRED_AT)))
                .isEqualTo(new ReserveTicketsRequest("o-1", "corr-1"));
        assertThat(mapper.toReserveTicketsRequest(null)).isNull();
    }

    @Test
    void shouldMapPaymentMessages() {
        assertThat(mapper.toProcessOrderPaymentRequest(
                new PaymentRequestedMessageRequestDto("o-1", "tok_1", "corr-1", OCCURRED_AT)))
                .isEqualTo(new ProcessOrderPaymentRequest("o-1", "tok_1", "corr-1"));
        assertThat(mapper.toProcessOrderPaymentRequest(null)).isNull();
    }
}
