package com.ticketing.domain.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SweeperPolicyTest {
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Test
    void shouldExposeSettings() {
        SweeperPolicy policy = new SweeperPolicy(LEASE, 100, 8, "host-1-abcd");

        assertThat(policy.lease()).isEqualTo(LEASE);
        assertThat(policy.batchSize()).isEqualTo(100);
        assertThat(policy.concurrency()).isEqualTo(8);
        assertThat(policy.instanceId()).isEqualTo("host-1-abcd");
    }

    @Test
    void shouldRejectInvalidSettings() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SweeperPolicy(Duration.ZERO, 100, 8, "host"));
        assertThatNullPointerException().isThrownBy(() -> new SweeperPolicy(null, 100, 8, "host"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SweeperPolicy(LEASE, 0, 8, "host"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SweeperPolicy(LEASE, 100, 0, "host"));
        assertThatIllegalArgumentException().isThrownBy(() -> new SweeperPolicy(LEASE, 100, 8, " "));
    }
}
