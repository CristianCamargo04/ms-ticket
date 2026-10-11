package com.ticketing.business.usecase.impl.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.domain.data.SweepResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SweepItemOutcomeTest {

    private static final SweepResult START = new SweepResult(true, 1, 2, 3, 4);

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "RELEASED,    2, 2, 3, 4",
        "FINALIZED,   1, 3, 3, 4",
        "REPUBLISHED, 1, 2, 4, 4",
        "FAILED,      1, 2, 3, 5",
        "UNCHANGED,   1, 2, 3, 4"})
    void shouldIncrementTheMatchingCounter(SweepItemOutcome outcome, int released, int finalized, int republished,
                                           int failed) {
        assertThat(outcome.addTo(START)).isEqualTo(new SweepResult(true, released, finalized, republished, failed));
    }

    @Test
    void shouldKeepTheLockFlag() {
        SweepResult notAcquired = new SweepResult(false, 0, 0, 0, 0);

        assertThat(SweepItemOutcome.RELEASED.addTo(notAcquired).lockAcquired()).isFalse();
    }
}
