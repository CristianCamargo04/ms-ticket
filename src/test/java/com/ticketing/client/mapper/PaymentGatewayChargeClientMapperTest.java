package com.ticketing.client.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.client.dto.payment.ChargeRequestDto;
import com.ticketing.client.dto.payment.ChargeResponseDto;
import com.ticketing.domain.exception.PaymentGatewayException;
import com.ticketing.domain.external_request.payment.PaymentChargeRequest;
import com.ticketing.domain.order.PaymentOutcome;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaymentGatewayChargeClientMapperTest {
    private final PaymentGatewayChargeClientMapper mapper = PaymentGatewayChargeClientMapper.INSTANCE;

    @Test
    void shouldMapChargeRequestWithoutTheIdempotencyKeyAndMaskTheTokenInToString() {
        ChargeRequestDto dto = mapper.toChargeRequest(new PaymentChargeRequest("order-1", "order-1", "customer-1",
                new BigDecimal("150.00"), "USD", "tok_secret"));

        assertThat(dto).isEqualTo(new ChargeRequestDto("order-1", "customer-1", new BigDecimal("150.00"), "USD",
                "tok_secret"));
        assertThat(dto.toString()).contains("order-1", "customer-1", "150.00", "USD", "paymentToken=***")
                .doesNotContain("tok_secret");
    }

    @Test
    void shouldMapApprovedChargeToApprovedOutcome() {
        PaymentOutcome outcome = mapper.toOutcome(new ChargeResponseDto("pay-1", "APPROVED", null));

        assertThat(outcome).isEqualTo(new PaymentOutcome.Approved("pay-1"));
    }

    @Test
    void shouldMapDeclinedChargeToDeclinedOutcome() {
        PaymentOutcome outcome = mapper.toOutcome(new ChargeResponseDto(null, "DECLINED", "insufficient_funds"));

        assertThat(outcome).isEqualTo(new PaymentOutcome.Declined("insufficient_funds"));
    }

    @Test
    void shouldRejectUnknownOrMissingStatus() {
        assertThatThrownBy(() -> mapper.toOutcome(new ChargeResponseDto("pay-1", "PENDING", null)))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageNotContaining("PENDING");
        assertThatThrownBy(() -> mapper.toOutcome(new ChargeResponseDto("pay-1", null, null)))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    void shouldRejectNullArguments() {
        assertThat(mapper.toChargeRequest(null)).isNull();
        assertThatNullPointerException().isThrownBy(() -> mapper.toOutcome(null));
    }
}
