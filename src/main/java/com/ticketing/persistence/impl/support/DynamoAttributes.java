package com.ticketing.persistence.impl.support;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Conversions shared by the DynamoDB adapters: {@link AttributeValue} factories for expression values and the storage
 * representation of domain types (instants as Number epoch milliseconds, implementation spec 6).
 */
public final class DynamoAttributes {

    private DynamoAttributes() {
    }

    /**
     * String attribute.
     *
     * @param value the value, never {@code null}
     * @return an {@code S} attribute
     */
    public static AttributeValue string(String value) {
        return AttributeValue.fromS(Objects.requireNonNull(value, "value"));
    }

    /**
     * Number attribute.
     *
     * @param value the value
     * @return an {@code N} attribute
     */
    public static AttributeValue number(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    /**
     * Number attribute holding an instant as epoch milliseconds.
     *
     * @param instant the instant, never {@code null}
     * @return an {@code N} attribute
     */
    public static AttributeValue epochMillis(Instant instant) {
        return number(Objects.requireNonNull(instant, "instant").toEpochMilli());
    }

    /**
     * List attribute of strings.
     *
     * @param values the values, never {@code null}
     * @return an {@code L} attribute whose elements are {@code S} attributes, in the given order
     */
    public static AttributeValue stringList(List<String> values) {
        return AttributeValue.fromL(values.stream().map(DynamoAttributes::string).toList());
    }

    /**
     * Storage form of an optional instant.
     *
     * @param instant the instant, may be {@code null}
     * @return epoch milliseconds, or {@code null}
     */
    public static Long toEpochMillis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    /**
     * Domain form of an optional stored instant.
     *
     * @param epochMillis epoch milliseconds, may be {@code null}
     * @return the instant, or {@code null}
     */
    public static Instant toInstant(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis);
    }

    /**
     * Guards a mandatory stored attribute so that a corrupted item fails with a clear message instead of an
     * unboxing {@code NullPointerException}.
     *
     * @param value     the attribute value read from the item
     * @param attribute the attribute name
     * @param <T>       value type
     * @return the value
     * @throws IllegalStateException when the value is missing
     */
    public static <T> T required(T value, String attribute) {
        if (value == null) {
            throw new IllegalStateException("Missing mandatory attribute: " + attribute);
        }
        return value;
    }
}
