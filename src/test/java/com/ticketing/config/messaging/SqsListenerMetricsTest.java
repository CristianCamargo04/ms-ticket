package com.ticketing.config.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.config.messaging.SqsListenerMetrics.MessageOutcome;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SqsListenerMetricsTest {

    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final SqsListenerMetrics metrics = new SqsListenerMetrics(registry);

    @Test
    void shouldCountAndTimeEveryOutcomePerQueue() {
        Timer.Sample sample = metrics.startProcessing();
        clock.add(Duration.ofMillis(250));
        metrics.record("orders", MessageOutcome.ACKED, sample);
        metrics.record("orders", MessageOutcome.FAILED, metrics.startProcessing());
        metrics.record("orders", MessageOutcome.FAILED, metrics.startProcessing());
        metrics.record("payments", MessageOutcome.POISON, metrics.startProcessing());

        assertThat(registry.get(SqsListenerMetrics.MESSAGES).tags("queue", "orders", "outcome", "acked").counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.get(SqsListenerMetrics.MESSAGES).tags("queue", "orders", "outcome", "failed").counter()
                .count()).isEqualTo(2.0);
        assertThat(registry.get(SqsListenerMetrics.MESSAGES).tags("queue", "payments", "outcome", "poison").counter()
                .count()).isEqualTo(1.0);
        Timer acked = registry.get(SqsListenerMetrics.PROCESSING).tags("queue", "orders", "outcome", "acked").timer();
        assertThat(acked.count()).isEqualTo(1);
        assertThat(acked.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(250.0);
    }

    @Test
    void shouldTrackInFlightMessagesWithOneGaugePerQueue() {
        AtomicInteger orders = metrics.inFlight("orders");
        orders.addAndGet(3);

        assertThat(metrics.inFlight("orders")).isSameAs(orders);
        assertThat(registry.get(SqsListenerMetrics.IN_FLIGHT).tag("queue", "orders").gauge().value()).isEqualTo(3.0);
        assertThat(metrics.inFlight("payments")).isNotSameAs(orders).hasValue(0);
        assertThat(registry.find(SqsListenerMetrics.IN_FLIGHT).gauges()).hasSize(2);
    }

    @Test
    void shouldExposeLowerCaseOutcomeTags() {
        assertThat(MessageOutcome.ACKED.tagValue()).isEqualTo("acked");
        assertThat(MessageOutcome.FAILED.tagValue()).isEqualTo("failed");
        assertThat(MessageOutcome.POISON.tagValue()).isEqualTo("poison");
    }

    @Test
    void shouldRequireARegistry() {
        assertThatNullPointerException().isThrownBy(() -> new SqsListenerMetrics(null));
    }
}
