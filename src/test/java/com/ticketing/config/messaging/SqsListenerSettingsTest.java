package com.ticketing.config.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.config.properties.TicketingProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;

class SqsListenerSettingsTest {
    private static final Duration SECOND = Duration.ofSeconds(1);
    private static final Duration THIRTY_SECONDS = Duration.ofSeconds(30);

    private static SqsListenerSettings valid() {
        return new SqsListenerSettings(10, 20, 60, 16, 2, Duration.ofSeconds(5), Duration.ofMinutes(5), SECOND,
                THIRTY_SECONDS, THIRTY_SECONDS);
    }

    @Test
    void shouldBuildFromMessagingPropertiesWithDefaultBackoffs() {
        TicketingProperties.Messaging messaging = new Binder()
                .bindOrCreate("ticketing", TicketingProperties.class).messaging();

        SqsListenerSettings settings = SqsListenerSettings.from(messaging, Duration.ofSeconds(45));

        assertThat(settings).isEqualTo(new SqsListenerSettings(10, 20, 60, 16, 2,
                SqsListenerSettings.DEFAULT_REDELIVERY_BASE_DELAY, SqsListenerSettings.DEFAULT_REDELIVERY_MAX_DELAY,
                SqsListenerSettings.DEFAULT_POLL_RETRY_MIN_BACKOFF, SqsListenerSettings.DEFAULT_POLL_RETRY_MAX_BACKOFF,
                Duration.ofMillis(40_500)));
        assertThat(SqsListenerSettings.DEFAULT_REDELIVERY_BASE_DELAY).isEqualTo(Duration.ofSeconds(5));
        assertThat(SqsListenerSettings.DEFAULT_REDELIVERY_MAX_DELAY).isEqualTo(Duration.ofSeconds(300));
        assertThatNullPointerException().isThrownBy(() -> SqsListenerSettings.from(null, THIRTY_SECONDS));
    }

    @ParameterizedTest(name = "receive count {0} -> {1}s")
    @CsvSource({"-1, 5", "0, 5", "1, 10", "2, 20", "3, 40", "4, 80", "5, 160", "6, 300", "30, 300", "2147483647, 300"})
    void shouldComputeExponentialRedeliveryDelayCappedAtTheMaximum(int receiveCount, long expectedSeconds) {
        assertThat(valid().redeliveryDelay(receiveCount)).isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    @Test
    void shouldAcceptBoundaryValues() {
        SqsListenerSettings settings = new SqsListenerSettings(1, 1, 43200, 1, 1, SECOND, Duration.ofHours(12),
                SECOND, SECOND, SECOND);

        assertThat(settings.redeliveryDelay(40)).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void shouldAcceptShortPollingAsDocumentedForTheWaitTimeProperty() {
        SqsListenerSettings settings = new SqsListenerSettings(10, 0, 60, 16, 2, SECOND, SECOND, SECOND, SECOND,
                SECOND);

        assertThat(settings.waitTimeSeconds()).isZero();
    }

    @ParameterizedTest(name = "visibility timeout {0}s -> handler timeout {1}ms")
    @CsvSource({"1, 900", "5, 4500", "60, 54000", "43200, 38880000"})
    void shouldBoundHandlersToNinetyPercentOfTheVisibilityTimeout(int visibilityTimeoutSeconds,
                                                                 long expectedMillis) {
        SqsListenerSettings settings = new SqsListenerSettings(10, 20, visibilityTimeoutSeconds, 16, 2, SECOND,
                SECOND, SECOND, SECOND, SECOND);

        assertThat(settings.handlerTimeout()).isEqualTo(Duration.ofMillis(expectedMillis));
    }

    @Test
    void shouldKeepTenPercentOfThePhaseTimeoutInReserveForTheForcedStop() {
        assertThat(SqsListenerSettings.drainBudget(THIRTY_SECONDS)).isEqualTo(Duration.ofSeconds(27));
        assertThat(SqsListenerSettings.drainBudget(Duration.ofNanos(1))).isEqualTo(Duration.ofNanos(1));
        assertThatNullPointerException().isThrownBy(() -> SqsListenerSettings.drainBudget(null))
                .withMessage("phaseTimeout");
    }

    @Test
    void shouldRejectOutOfRangeCounts() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SqsListenerSettings(0, 20, 60, 16, 2,
                SECOND, SECOND, SECOND, SECOND, SECOND)).withMessageContaining("maxMessages");
        assertThatIllegalArgumentException().isThrownBy(() -> new SqsListenerSettings(11, 20, 60, 16, 2,
                SECOND, SECOND, SECOND, SECOND, SECOND)).withMessageContaining("maxMessages");
        assertThatIllegalArgumentException().isThrownBy(() -> new SqsListenerSettings(10, -1, 60, 16, 2,
                SECOND, SECOND, SECOND, SECOND, SECOND)).withMessageContaining("waitTimeSeconds");
        assertThatIllegalArgumentException().isThrownBy(() -> new SqsListenerSettings(10, 21, 60, 16, 2,
                SECOND, SECOND, SECOND, SECOND, SECOND)).withMessageContaining("waitTimeSeconds");
        assertThatIllegalArgumentException().isThrownBy(() -> new SqsListenerSettings(10, 20, 0, 16, 2,
                SECOND, SECOND, SECOND, SECOND, SECOND)).withMessageContaining("visibilityTimeoutSeconds");
        assertThatIllegalArgumentException().isThrownBy(() -> new SqsListenerSettings(10, 20, 43201, 16, 2,
                SECOND, SECOND, SECOND, SECOND, SECOND)).withMessageContaining("visibilityTimeoutSeconds");
        assertThatIllegalArgumentException().isThrownBy(() -> new SqsListenerSettings(10, 20, 60, 0, 2,
                SECOND, SECOND, SECOND, SECOND, SECOND)).withMessageContaining("concurrency");
        assertThatIllegalArgumentException().isThrownBy(() -> new SqsListenerSettings(10, 20, 60, 16, 0,
                SECOND, SECOND, SECOND, SECOND, SECOND)).withMessageContaining("pollers");
    }

    @Test
    void shouldRejectMissingOrNonPositiveDurations() {
        assertThatNullPointerException().isThrownBy(() -> new SqsListenerSettings(10, 20, 60, 16, 2,
                null, SECOND, SECOND, SECOND, SECOND)).withMessage("redeliveryBaseDelay");
        assertThatIllegalArgumentException().isThrownBy(() -> new SqsListenerSettings(10, 20, 60, 16, 2,
                Duration.ZERO, SECOND, SECOND, SECOND, SECOND)).withMessageContaining("redeliveryBaseDelay");
        assertThatIllegalArgumentException().isThrownBy(() -> new SqsListenerSettings(10, 20, 60, 16, 2,
                SECOND, SECOND.negated(), SECOND, SECOND, SECOND)).withMessageContaining("redeliveryMaxDelay");
        assertThatIllegalArgumentException().isThrownBy(() -> new SqsListenerSettings(10, 20, 60, 16, 2,
                SECOND, SECOND, Duration.ZERO, SECOND, SECOND)).withMessageContaining("pollRetryMinBackoff");
        assertThatIllegalArgumentException().isThrownBy(() -> new SqsListenerSettings(10, 20, 60, 16, 2,
                SECOND, SECOND, SECOND, Duration.ZERO, SECOND)).withMessageContaining("pollRetryMaxBackoff");
        assertThatIllegalArgumentException().isThrownBy(() -> new SqsListenerSettings(10, 20, 60, 16, 2,
                SECOND, SECOND, SECOND, SECOND, Duration.ZERO)).withMessageContaining("shutdownTimeout");
    }

    @Test
    void shouldRejectInconsistentBackoffBounds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SqsListenerSettings(10, 20, 60, 16, 2,
                THIRTY_SECONDS, SECOND, SECOND, SECOND, SECOND)).withMessageContaining("redeliveryMaxDelay");
        assertThatIllegalArgumentException().isThrownBy(() -> new SqsListenerSettings(10, 20, 60, 16, 2,
                SECOND, Duration.ofHours(13), SECOND, SECOND, SECOND)).withMessageContaining("12h");
        assertThatIllegalArgumentException().isThrownBy(() -> new SqsListenerSettings(10, 20, 60, 16, 2,
                SECOND, SECOND, THIRTY_SECONDS, SECOND, SECOND)).withMessageContaining("pollRetryMaxBackoff");
    }
}
