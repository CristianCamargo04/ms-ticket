package com.ticketing.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class EventPolicyTest {

    private static final Duration INTERVAL = Duration.ofSeconds(1);

    @Test
    void shouldExposeSettings() {
        EventPolicy policy = new EventPolicy(20000, INTERVAL, 4);

        assertThat(policy.maxCapacity()).isEqualTo(20000);
        assertThat(policy.availabilityStreamInterval()).isEqualTo(INTERVAL);
        assertThat(policy.ticketWriteConcurrency()).isEqualTo(4);
    }

    @Test
    void shouldAcceptCapacityUpToTheTicketIdFormatLimit() {
        assertThat(new EventPolicy(999_999, INTERVAL, 1).maxCapacity()).isEqualTo(999_999);
    }

    @Test
    void shouldRejectInvalidSettings() {
        assertThatIllegalArgumentException().isThrownBy(() -> new EventPolicy(0, INTERVAL, 4));
        assertThatIllegalArgumentException().isThrownBy(() -> new EventPolicy(1_000_000, INTERVAL, 4));
        assertThatIllegalArgumentException().isThrownBy(() -> new EventPolicy(100, Duration.ZERO, 4));
        assertThatNullPointerException().isThrownBy(() -> new EventPolicy(100, null, 4));
        assertThatIllegalArgumentException().isThrownBy(() -> new EventPolicy(100, INTERVAL, 0));
    }
}
