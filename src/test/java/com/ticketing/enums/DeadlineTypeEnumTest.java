package com.ticketing.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeadlineTypeEnumTest {
    @Test
    void shouldDeclareThePersistedConstants() {
        assertThat(DeadlineTypeEnum.values()).containsExactly(DeadlineTypeEnum.DISPATCH,
                DeadlineTypeEnum.HOLD_EXPIRATION, DeadlineTypeEnum.PAYMENT_CONFIRMATION);
    }
}
