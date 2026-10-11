package com.ticketing.persistence.impl.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BatchRetryPolicyTest {

    private final BatchRetryPolicy policy = BatchRetryPolicy.DEFAULT;

    @Test
    void shouldAllowRetriesUntilTheAttemptBudgetIsSpent() {
        assertThat(policy.maxAttempts()).isEqualTo(8);
        assertThat(policy.canRetry(1)).isTrue();
        assertThat(policy.canRetry(7)).isTrue();
        assertThat(policy.canRetry(8)).isFalse();
    }

    @Test
    void shouldDoubleTheDelayUpToTheCap() {
        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofMillis(50));
        assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.delayAfter(4)).isEqualTo(Duration.ofMillis(400));
        assertThat(policy.delayAfter(6)).isEqualTo(Duration.ofMillis(1600));
        assertThat(policy.delayAfter(7)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayAfter(1000)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayAfter(0)).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    void shouldValidateTheSettings() {
        Duration one = Duration.ofMillis(1);
        assertThatThrownBy(() -> new BatchRetryPolicy(0, one, one)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BatchRetryPolicy(1, Duration.ZERO, one))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BatchRetryPolicy(1, one.negated(), one))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BatchRetryPolicy(1, Duration.ofMillis(2), one))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BatchRetryPolicy(1, null, one)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BatchRetryPolicy(1, one, null)).isInstanceOf(NullPointerException.class);
    }
}
