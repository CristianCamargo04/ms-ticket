package com.ticketing.domain.validation;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;

/**
 * Invariant checks shared by the domain records.
 *
 * <p>A violated invariant is a programming or data-integrity error, never a client input error: {@code null} values
 * raise {@link NullPointerException} and any other violation raises {@link IllegalArgumentException}. Client input
 * is validated beforehand (presentation DTOs and use cases raise {@code DomainValidationException}).
 */
public final class DomainPreconditions {

    private DomainPreconditions() {
    }

    /**
     * Requires a non-null, non-blank string.
     *
     * @param value the value to check
     * @param name  the name used in the error message
     * @return {@code value}
     */
    public static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * Requires {@code value >= 0}.
     *
     * @param value the value to check
     * @param name  the name used in the error message
     * @return {@code value}
     */
    public static int requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0 but was " + value);
        }
        return value;
    }

    /**
     * Requires {@code value >= 0}.
     *
     * @param value the value to check
     * @param name  the name used in the error message
     * @return {@code value}
     */
    public static long requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0 but was " + value);
        }
        return value;
    }

    /**
     * Requires a non-null amount {@code >= 0}.
     *
     * @param value the value to check
     * @param name  the name used in the error message
     * @return {@code value}
     */
    public static BigDecimal requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must be >= 0 but was " + value.toPlainString());
        }
        return value;
    }

    /**
     * Requires {@code min <= value <= max}.
     *
     * @param value the value to check
     * @param min   inclusive lower bound
     * @param max   inclusive upper bound
     * @param name  the name used in the error message
     * @return {@code value}
     */
    public static int requireBetween(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + " but was " + value);
        }
        return value;
    }

    /**
     * Requires {@code min <= value <= max} ({@code NaN} is rejected).
     *
     * @param value the value to check
     * @param min   inclusive lower bound
     * @param max   inclusive upper bound
     * @param name  the name used in the error message
     * @return {@code value}
     */
    public static double requireBetween(double value, double min, double max, String name) {
        if (!(value >= min && value <= max)) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + " but was " + value);
        }
        return value;
    }

    /**
     * Requires a non-null, strictly positive duration.
     *
     * @param value the value to check
     * @param name  the name used in the error message
     * @return {@code value}
     */
    public static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
        return value;
    }

    /**
     * Requires a non-null duration {@code >= 0}.
     *
     * @param value the value to check
     * @param name  the name used in the error message
     * @return {@code value}
     */
    public static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative but was " + value);
        }
        return value;
    }

    /**
     * Requires a non-null, non-empty collection.
     *
     * @param value the value to check
     * @param name  the name used in the error message
     * @param <C>   collection type
     * @return {@code value}
     */
    public static <C extends Collection<?>> C requireNotEmpty(C value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    /**
     * Requires an arbitrary condition.
     *
     * @param condition the condition that must hold
     * @param message   the error message when it does not hold
     */
    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
