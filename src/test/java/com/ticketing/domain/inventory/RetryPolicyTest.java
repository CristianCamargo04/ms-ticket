package com.ticketing.domain.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {
    private static final Duration MIN = Duration.ofMillis(25);
    private static final Duration MAX = Duration.ofSeconds(1);

    @Test
    void shouldExposeSettings() {
        RetryPolicy policy = new RetryPolicy(6, MIN, MAX, 0.5);

        assertThat(policy.maxAttempts()).isEqualTo(6);
        assertThat(policy.minBackoff()).isEqualTo(MIN);
        assertThat(policy.maxBackoff()).isEqualTo(MAX);
        assertThat(policy.jitter()).isEqualTo(0.5);
    }

    @Test
    void shouldAllowNoRetryAndEqualBounds() {
        RetryPolicy policy = new RetryPolicy(0, Duration.ZERO, Duration.ZERO, 0.0);

        assertThat(policy.maxAttempts()).isZero();
    }

    @Test
    void shouldRejectInvalidSettings() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RetryPolicy(-1, MIN, MAX, 0.5));
        assertThatIllegalArgumentException().isThrownBy(() -> new RetryPolicy(1, Duration.ofMillis(-1), MAX, 0.5));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryPolicy(1, MAX, MIN, 0.5))
                .withMessage("maxBackoff must be >= minBackoff");
        assertThatIllegalArgumentException().isThrownBy(() -> new RetryPolicy(1, MIN, MAX, 1.5));
        assertThatNullPointerException().isThrownBy(() -> new RetryPolicy(1, null, MAX, 0.5));
        assertThatNullPointerException().isThrownBy(() -> new RetryPolicy(1, MIN, null, 0.5));
    }
}
