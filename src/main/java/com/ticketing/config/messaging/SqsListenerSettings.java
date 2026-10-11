package com.ticketing.config.messaging;

import com.ticketing.config.properties.TicketingProperties;
import java.time.Duration;
import java.util.Objects;

public record SqsListenerSettings(
        int maxMessages,
        int waitTimeSeconds,
        int visibilityTimeoutSeconds,
        int concurrency,
        int pollers,
        Duration redeliveryBaseDelay,
        Duration redeliveryMaxDelay,
        Duration pollRetryMinBackoff,
        Duration pollRetryMaxBackoff,
        Duration shutdownTimeout) {
    public static final Duration DEFAULT_REDELIVERY_BASE_DELAY = Duration.ofSeconds(5);

    public static final Duration DEFAULT_REDELIVERY_MAX_DELAY = Duration.ofMinutes(5);

    public static final Duration DEFAULT_POLL_RETRY_MIN_BACKOFF = Duration.ofSeconds(1);

    public static final Duration DEFAULT_POLL_RETRY_MAX_BACKOFF = Duration.ofSeconds(30);

    static final Duration MAX_VISIBILITY_TIMEOUT = Duration.ofHours(12);

    private static final int MAX_BACKOFF_EXPONENT = 30;

    private static final int RESERVE_DIVISOR = 10;

    public SqsListenerSettings {
        requireBetween(maxMessages, 1, 10, "maxMessages");
        requireBetween(waitTimeSeconds, 0, 20, "waitTimeSeconds");
        requireBetween(visibilityTimeoutSeconds, 1, (int) MAX_VISIBILITY_TIMEOUT.toSeconds(), "visibilityTimeoutSeconds");
        requireBetween(concurrency, 1, Integer.MAX_VALUE, "concurrency");
        requireBetween(pollers, 1, Integer.MAX_VALUE, "pollers");
        requirePositive(redeliveryBaseDelay, "redeliveryBaseDelay");
        requirePositive(redeliveryMaxDelay, "redeliveryMaxDelay");
        requirePositive(pollRetryMinBackoff, "pollRetryMinBackoff");
        requirePositive(pollRetryMaxBackoff, "pollRetryMaxBackoff");
        requirePositive(shutdownTimeout, "shutdownTimeout");
        if (redeliveryMaxDelay.compareTo(redeliveryBaseDelay) < 0 || redeliveryMaxDelay.compareTo(MAX_VISIBILITY_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "redeliveryMaxDelay must be between redeliveryBaseDelay and 12h but was " + redeliveryMaxDelay);
        }
        if (pollRetryMaxBackoff.compareTo(pollRetryMinBackoff) < 0) {
            throw new IllegalArgumentException("pollRetryMaxBackoff must not be lower than pollRetryMinBackoff");
        }
    }

    public static SqsListenerSettings from(TicketingProperties.Messaging messaging, Duration phaseTimeout) {
        Objects.requireNonNull(messaging, "messaging");
        return new SqsListenerSettings(messaging.maxMessages(), messaging.waitTimeSeconds(),
                messaging.visibilityTimeoutSeconds(), messaging.concurrency(), messaging.pollers(),
                DEFAULT_REDELIVERY_BASE_DELAY, DEFAULT_REDELIVERY_MAX_DELAY,
                DEFAULT_POLL_RETRY_MIN_BACKOFF, DEFAULT_POLL_RETRY_MAX_BACKOFF, drainBudget(phaseTimeout));
    }

    static Duration drainBudget(Duration phaseTimeout) {
        Objects.requireNonNull(phaseTimeout, "phaseTimeout");
        return phaseTimeout.minus(phaseTimeout.dividedBy(RESERVE_DIVISOR));
    }

    public Duration handlerTimeout() {
        Duration visibilityTimeout = Duration.ofSeconds(visibilityTimeoutSeconds);
        return visibilityTimeout.minus(visibilityTimeout.dividedBy(RESERVE_DIVISOR));
    }

    public Duration redeliveryDelay(int receiveCount) {
        int exponent = Math.clamp(receiveCount, 0, MAX_BACKOFF_EXPONENT);
        Duration delay = redeliveryBaseDelay.multipliedBy(1L << exponent);
        return delay.compareTo(redeliveryMaxDelay) > 0 ? redeliveryMaxDelay : delay;
    }

    private static void requireBetween(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + " but was " + value);
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
    }
}
