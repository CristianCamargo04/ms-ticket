package com.ticketing.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentOutcomeTest {

    private static String describe(PaymentOutcome outcome) {
        return switch (outcome) {
            case PaymentOutcome.Approved(String paymentReference) -> "approved:" + paymentReference;
            case PaymentOutcome.Declined(String reason) -> "declined:" + reason;
            case PaymentOutcome.NotFound() -> "not-found";
        };
    }

    static Stream<Arguments> everyOutcome() {
        return Stream.of(
                Arguments.of(new PaymentOutcome.Approved("pay-1"), "approved:pay-1"),
                Arguments.of(new PaymentOutcome.Declined("INSUFFICIENT_FUNDS"), "declined:INSUFFICIENT_FUNDS"),
                Arguments.of(new PaymentOutcome.Declined(null), "declined:null"),
                Arguments.of(new PaymentOutcome.NotFound(), "not-found"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("everyOutcome")
    void shouldBeExhaustivelyPatternMatchable(PaymentOutcome outcome, String expected) {
        assertThat(describe(outcome)).isEqualTo(expected);
    }

    @Test
    void shouldRequireApprovedPaymentReference() {
        assertThatNullPointerException().isThrownBy(() -> new PaymentOutcome.Approved(null));
        assertThatIllegalArgumentException().isThrownBy(() -> new PaymentOutcome.Approved(" "));
    }
}
