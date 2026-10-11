package com.ticketing.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DomainPreconditionsTest {
    @Test
    void shouldReturnNonBlankString() {
        assertThat(DomainPreconditions.requireNonBlank("value", "name")).isEqualTo("value");
    }

    @Test
    void shouldRejectNullString() {
        assertThatNullPointerException()
                .isThrownBy(() -> DomainPreconditions.requireNonBlank(null, "name"))
                .withMessage("name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void shouldRejectBlankString(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainPreconditions.requireNonBlank(value, "name"))
                .withMessage("name must not be blank");
    }

    @Test
    void shouldAcceptNonNegativeInt() {
        assertThat(DomainPreconditions.requireNonNegative(0, "count")).isZero();
        assertThat(DomainPreconditions.requireNonNegative(3, "count")).isEqualTo(3);
    }

    @Test
    void shouldRejectNegativeInt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainPreconditions.requireNonNegative(-1, "count"))
                .withMessage("count must be >= 0 but was -1");
    }

    @Test
    void shouldAcceptNonNegativeLong() {
        assertThat(DomainPreconditions.requireNonNegative(0L, "version")).isZero();
    }

    @Test
    void shouldRejectNegativeLong() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainPreconditions.requireNonNegative(-5L, "version"))
                .withMessage("version must be >= 0 but was -5");
    }

    @Test
    void shouldAcceptNonNegativeAmount() {
        assertThat(DomainPreconditions.requireNonNegative(BigDecimal.ZERO, "price")).isEqualByComparingTo("0");
    }

    @Test
    void shouldRejectNegativeOrNullAmount() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainPreconditions.requireNonNegative(new BigDecimal("-0.01"), "price"))
                .withMessage("price must be >= 0 but was -0.01");
        assertThatNullPointerException()
                .isThrownBy(() -> DomainPreconditions.requireNonNegative((BigDecimal) null, "price"));
    }

    @Test
    void shouldAcceptIntWithinBounds() {
        assertThat(DomainPreconditions.requireBetween(1, 1, 3, "size")).isEqualTo(1);
        assertThat(DomainPreconditions.requireBetween(3, 1, 3, "size")).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4})
    void shouldRejectIntOutOfBounds(int value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainPreconditions.requireBetween(value, 1, 3, "size"))
                .withMessage("size must be between 1 and 3 but was " + value);
    }

    @Test
    void shouldAcceptDoubleWithinBounds() {
        assertThat(DomainPreconditions.requireBetween(0.0, 0.0, 1.0, "jitter")).isZero();
        assertThat(DomainPreconditions.requireBetween(1.0, 0.0, 1.0, "jitter")).isEqualTo(1.0);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN})
    void shouldRejectDoubleOutOfBounds(double value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainPreconditions.requireBetween(value, 0.0, 1.0, "jitter"));
    }

    @Test
    void shouldAcceptPositiveDuration() {
        assertThat(DomainPreconditions.requirePositive(Duration.ofMillis(1), "ttl")).hasMillis(1);
    }

    @Test
    void shouldRejectNonPositiveOrNullDuration() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainPreconditions.requirePositive(Duration.ZERO, "ttl"))
                .withMessage("ttl must be positive but was PT0S");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainPreconditions.requirePositive(Duration.ofSeconds(-1), "ttl"));
        assertThatNullPointerException().isThrownBy(() -> DomainPreconditions.requirePositive(null, "ttl"));
    }

    @Test
    void shouldAcceptNonNegativeDuration() {
        assertThat(DomainPreconditions.requireNonNegative(Duration.ZERO, "backoff")).isZero();
    }

    @Test
    void shouldRejectNegativeOrNullDuration() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainPreconditions.requireNonNegative(Duration.ofMillis(-1), "backoff"))
                .withMessage("backoff must not be negative but was PT-0.001S");
        assertThatNullPointerException()
                .isThrownBy(() -> DomainPreconditions.requireNonNegative((Duration) null, "backoff"));
    }

    @Test
    void shouldAcceptNonEmptyCollection() {
        List<String> values = List.of("a");

        assertThat(DomainPreconditions.requireNotEmpty(values, "ids")).isSameAs(values);
    }

    @Test
    void shouldRejectEmptyOrNullCollection() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainPreconditions.requireNotEmpty(List.of(), "ids"))
                .withMessage("ids must not be empty");
        assertThatNullPointerException()
                .isThrownBy(() -> DomainPreconditions.requireNotEmpty((List<String>) null, "ids"));
    }

    @Test
    void shouldEnforceArbitraryCondition() {
        DomainPreconditions.require(true, "never thrown");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> DomainPreconditions.require(false, "broken invariant"))
                .withMessage("broken invariant");
    }
}
