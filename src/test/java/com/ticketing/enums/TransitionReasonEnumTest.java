package com.ticketing.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TransitionReasonEnumTest {

    @Test
    void shouldDeclareThePersistedConstants() {
        assertThat(TransitionReasonEnum.values()).containsExactly(
                TransitionReasonEnum.RESERVATION, TransitionReasonEnum.COMPLIMENTARY_ISSUED,
                TransitionReasonEnum.CUSTOMER_CONFIRMATION, TransitionReasonEnum.PAYMENT_APPROVED,
                TransitionReasonEnum.PAYMENT_DECLINED, TransitionReasonEnum.HOLD_EXPIRED,
                TransitionReasonEnum.CONFIRMATION_TIMEOUT, TransitionReasonEnum.CUSTOMER_CANCELLATION);
    }
}
