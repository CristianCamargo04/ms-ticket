package com.ticketing.domain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class SweepResultTest {

    @Test
    void shouldCreateSkippedResult() {
        assertThat(SweepResult.skipped()).isEqualTo(new SweepResult(false, 0, 0, 0, 0));
    }

    @Test
    void shouldExposeCounters() {
        SweepResult result = new SweepResult(true, 3, 2, 1, 4);

        assertThat(result.lockAcquired()).isTrue();
        assertThat(result.released()).isEqualTo(3);
        assertThat(result.finalized()).isEqualTo(2);
        assertThat(result.republished()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(4);
    }

    @Test
    void shouldRejectNegativeCounters() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SweepResult(true, -1, 0, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new SweepResult(true, 0, -1, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new SweepResult(true, 0, 0, -1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new SweepResult(true, 0, 0, 0, -1));
    }
}
