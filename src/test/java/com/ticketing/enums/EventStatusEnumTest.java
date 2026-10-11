package com.ticketing.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventStatusEnumTest {

    @Test
    void shouldDeclareThePersistedConstants() {
        assertThat(EventStatusEnum.values())
                .containsExactly(EventStatusEnum.PUBLISHING, EventStatusEnum.ON_SALE, EventStatusEnum.CLOSED);
        assertThat(EventStatusEnum.valueOf("ON_SALE")).isSameAs(EventStatusEnum.ON_SALE);
    }
}
