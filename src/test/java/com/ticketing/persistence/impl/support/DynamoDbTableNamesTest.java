package com.ticketing.persistence.impl.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DynamoDbTableNamesTest {
    @Test
    void shouldExposeConfiguredNames() {
        DynamoDbTableNames names = new DynamoDbTableNames("e", "t", "o", "tt", "l", 8);

        assertThat(names.events()).isEqualTo("e");
        assertThat(names.tickets()).isEqualTo("t");
        assertThat(names.orders()).isEqualTo("o");
        assertThat(names.ticketTransitions()).isEqualTo("tt");
        assertThat(names.distributedLocks()).isEqualTo("l");
        assertThat(names.deadlineShards()).isEqualTo(8);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void shouldRejectBlankNames(int blankIndex) {
        String[] values = {"e", "t", "o", "tt", "l"};
        values[blankIndex] = " ";

        assertThatThrownBy(() -> new DynamoDbTableNames(values[0], values[1], values[2], values[3], values[4], 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullNames() {
        assertThatThrownBy(() -> new DynamoDbTableNames(null, "t", "o", "tt", "l", 1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRequireAtLeastOneDeadlineShard() {
        assertThatThrownBy(() -> new DynamoDbTableNames("e", "t", "o", "tt", "l", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deadlineShards");
    }
}
