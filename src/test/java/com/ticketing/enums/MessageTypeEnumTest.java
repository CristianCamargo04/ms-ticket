package com.ticketing.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MessageTypeEnumTest {

    @Test
    void shouldDeclareTheMessageAttributeValues() {
        assertThat(MessageTypeEnum.values())
                .containsExactly(MessageTypeEnum.ORDER_RESERVATION_REQUESTED, MessageTypeEnum.PAYMENT_REQUESTED);
    }
}
