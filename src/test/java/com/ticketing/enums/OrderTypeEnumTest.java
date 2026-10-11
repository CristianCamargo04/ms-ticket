package com.ticketing.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderTypeEnumTest {
    @Test
    void shouldDeclareThePersistedConstants() {
        assertThat(OrderTypeEnum.values()).containsExactly(OrderTypeEnum.STANDARD, OrderTypeEnum.COMPLIMENTARY);
        assertThat(OrderTypeEnum.valueOf("COMPLIMENTARY")).isSameAs(OrderTypeEnum.COMPLIMENTARY);
    }
}
