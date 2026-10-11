package com.ticketing.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
    private static final OrderPolicy POLICY = new OrderPolicy(Duration.ofMinutes(10), Duration.ofMinutes(5),
            Duration.ofSeconds(30), 10, 40, 3);

    @Test
    void shouldComputeHoldExpiration() {
        assertThat(POLICY.holdExpiresAt(NOW)).isEqualTo(Instant.parse("2026-10-10T10:10:00Z"));
    }

    @Test
    void shouldComputeConfirmationDeadline() {
        assertThat(POLICY.confirmationDeadline(NOW)).isEqualTo(Instant.parse("2026-10-10T10:05:00Z"));
    }

    @Test
    void shouldComputeDispatchDeadline() {
        assertThat(POLICY.dispatchDeadline(NOW)).isEqualTo(Instant.parse("2026-10-10T10:00:30Z"));
    }

    @Test
    void shouldRejectNullInstants() {
        assertThatNullPointerException().isThrownBy(() -> POLICY.holdExpiresAt(null));
        assertThatNullPointerException().isThrownBy(() -> POLICY.confirmationDeadline(null));
        assertThatNullPointerException().isThrownBy(() -> POLICY.dispatchDeadline(null));
    }

    @Test
    void shouldExposeLimits() {
        assertThat(POLICY.maxTicketsPerOrder()).isEqualTo(10);
        assertThat(POLICY.maxComplimentaryPerRequest()).isEqualTo(40);
        assertThat(POLICY.candidateFactor()).isEqualTo(3);
    }

    @Test
    void shouldAcceptTheTransactionLimit() {
        OrderPolicy policy = new OrderPolicy(Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofSeconds(30),
                49, 49, 1);

        assertThat(policy.maxTicketsPerOrder()).isEqualTo(49);
    }

    @Test
    void shouldRejectInvalidSettings() {
        Duration ttl = Duration.ofMinutes(1);
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPolicy(Duration.ZERO, ttl, ttl, 10, 40, 3));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPolicy(ttl, Duration.ZERO, ttl, 10, 40, 3));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPolicy(ttl, ttl, Duration.ZERO, 10, 40, 3));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPolicy(ttl, ttl, ttl, 0, 40, 3));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPolicy(ttl, ttl, ttl, 50, 40, 3));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPolicy(ttl, ttl, ttl, 10, 50, 3));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderPolicy(ttl, ttl, ttl, 10, 40, 0));
        assertThatNullPointerException().isThrownBy(() -> new OrderPolicy(null, ttl, ttl, 10, 40, 3));
    }
}
