package com.ticketing.presentation.impl.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.util.HashUtil;
import org.junit.jupiter.api.Test;

class RequestFingerprintCalculatorTest {

    private final RequestFingerprintCalculator calculator = new RequestFingerprintCalculator();

    @Test
    void shouldHashTheCanonicalPlaceOrderString() {
        assertThat(calculator.placeOrder("e-1", 2)).isEqualTo(HashUtil.sha256Hex("place-order|e-1|2"))
                .hasSize(64);
    }

    @Test
    void shouldHashTheCanonicalComplimentaryString() {
        assertThat(calculator.issueComplimentary("e-1", "vip-7", 4))
                .isEqualTo(HashUtil.sha256Hex("issue-complimentary|e-1|vip-7|4"));
    }

    @Test
    void shouldBeDeterministicAndSensitiveToEveryField() {
        assertThat(calculator.placeOrder("e-1", 2)).isEqualTo(calculator.placeOrder("e-1", 2))
                .isNotEqualTo(calculator.placeOrder("e-1", 3))
                .isNotEqualTo(calculator.placeOrder("e-2", 2));
        assertThat(calculator.issueComplimentary("e-1", "vip-7", 4))
                .isNotEqualTo(calculator.issueComplimentary("e-1", "vip-8", 4));
    }

    @Test
    void shouldEscapeSeparatorsSoDifferentFieldsNeverCollide() {
        assertThat(RequestFingerprintCalculator.canonical("op", "a|b", "c")).isEqualTo("op|a\\|b|c");
        assertThat(RequestFingerprintCalculator.canonical("op", "a\\", "b")).isEqualTo("op|a\\\\|b");
        assertThat(calculator.issueComplimentary("e|vip", "x", 1))
                .isNotEqualTo(calculator.issueComplimentary("e", "vip|x", 1));
    }

    @Test
    void shouldRejectMissingFields() {
        assertThatNullPointerException().isThrownBy(() -> calculator.placeOrder(null, 1));
        assertThatNullPointerException().isThrownBy(() -> RequestFingerprintCalculator.canonical(null, "a"));
    }
}
