package com.ticketing.persistence.impl.support;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public final class DynamoAttributes {
    private DynamoAttributes() {
    }

    public static AttributeValue string(String value) {
        return AttributeValue.fromS(Objects.requireNonNull(value, "value"));
    }

    public static AttributeValue number(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    public static AttributeValue epochMillis(Instant instant) {
        return number(Objects.requireNonNull(instant, "instant").toEpochMilli());
    }

    public static Instant storedPrecision(Instant instant) {
        return Instant.ofEpochMilli(Objects.requireNonNull(instant, "instant").toEpochMilli());
    }

    public static AttributeValue stringList(List<String> values) {
        return AttributeValue.fromL(values.stream().map(DynamoAttributes::string).toList());
    }

    public static Long toEpochMillis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    public static Instant toInstant(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis);
    }

    public static <T> T required(T value, String attribute) {
        if (value == null) {
            throw new IllegalStateException("Missing mandatory attribute: " + attribute);
        }
        return value;
    }
}
