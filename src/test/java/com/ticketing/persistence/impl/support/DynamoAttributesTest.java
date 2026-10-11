package com.ticketing.persistence.impl.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class DynamoAttributesTest {

    private static final Instant INSTANT = Instant.parse("2026-10-10T12:00:00.123Z");

    @Test
    void shouldBuildStringAndNumberAttributes() {
        assertThat(DynamoAttributes.string("abc")).isEqualTo(AttributeValue.fromS("abc"));
        assertThat(DynamoAttributes.number(42)).isEqualTo(AttributeValue.fromN("42"));
        assertThat(DynamoAttributes.epochMillis(INSTANT)).isEqualTo(AttributeValue.fromN("1791633600123"));
    }

    @Test
    void shouldBuildAnOrderedListOfStrings() {
        assertThat(DynamoAttributes.stringList(List.of("b", "a"))).isEqualTo(
                AttributeValue.fromL(List.of(AttributeValue.fromS("b"), AttributeValue.fromS("a"))));
    }

    @Test
    void shouldRejectNullValues() {
        assertThatThrownBy(() -> DynamoAttributes.string(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DynamoAttributes.epochMillis(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldConvertOptionalInstants() {
        assertThat(DynamoAttributes.toEpochMillis(INSTANT)).isEqualTo(1_791_633_600_123L);
        assertThat(DynamoAttributes.toEpochMillis(null)).isNull();
        assertThat(DynamoAttributes.toInstant(1_791_633_600_123L)).isEqualTo(INSTANT);
        assertThat(DynamoAttributes.toInstant(null)).isNull();
    }

    @Test
    void shouldGuardMandatoryAttributes() {
        assertThat(DynamoAttributes.required(5, "quantity")).isEqualTo(5);
        assertThatThrownBy(() -> DynamoAttributes.required(null, "quantity"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing mandatory attribute: quantity");
    }
}
