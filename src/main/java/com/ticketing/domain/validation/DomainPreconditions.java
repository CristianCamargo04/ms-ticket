package com.ticketing.domain.validation;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;

public final class DomainPreconditions {
    private DomainPreconditions() {
    }

    public static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static int requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0 but was " + value);
        }
        return value;
    }

    public static long requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0 but was " + value);
        }
        return value;
    }

    public static BigDecimal requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must be >= 0 but was " + value.toPlainString());
        }
        return value;
    }

    public static int requireBetween(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + " but was " + value);
        }
        return value;
    }

    public static double requireBetween(double value, double min, double max, String name) {
        if (!(value >= min && value <= max)) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + " but was " + value);
        }
        return value;
    }

    public static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
        return value;
    }

    public static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative but was " + value);
        }
        return value;
    }

    public static <C extends Collection<?>> C requireNotEmpty(C value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
