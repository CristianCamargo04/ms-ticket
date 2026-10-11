package com.ticketing.config.messaging;

import com.ticketing.config.properties.TicketingProperties;
import java.time.Duration;
import java.util.Objects;

/**
 * Runtime settings of the {@link SqsListenerContainer}.
 *
 * <p>Long polling is mandatory ({@code waitTimeSeconds >= 1}): with a zero wait time an idle queue would turn every
 * poller into a hot loop of empty {@code ReceiveMessage} calls.
 *
 * @param maxMessages              messages per {@code ReceiveMessage} call (1..10)
 * @param waitTimeSeconds          long polling wait (1..20)
 * @param visibilityTimeoutSeconds visibility timeout requested for received messages (1..43200); must exceed the
 *                                 longest expected processing time
 * @param concurrency              messages processed in parallel per queue, {@code >= 1}
 * @param pollers                  concurrent receive loops per queue, {@code >= 1}
 * @param redeliveryBaseDelay      base of the exponential redelivery backoff applied to a failed message
 * @param redeliveryMaxDelay       upper bound of the redelivery backoff (at most 12 hours, the SQS limit)
 * @param pollRetryMinBackoff      first delay before retrying a failed SQS call (queue resolution, polling)
 * @param pollRetryMaxBackoff      upper bound of the SQS call retry delay
 * @param shutdownTimeout          maximum time {@code stop} waits for in-flight messages before cancelling them
 */
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

    /** Base of the redelivery backoff: a message failing for the n-th time becomes visible after 2^n x 5 s. */
    public static final Duration DEFAULT_REDELIVERY_BASE_DELAY = Duration.ofSeconds(5);

    /** Upper bound of the redelivery backoff. */
    public static final Duration DEFAULT_REDELIVERY_MAX_DELAY = Duration.ofMinutes(5);

    /** First delay before retrying a failed SQS call. */
    public static final Duration DEFAULT_POLL_RETRY_MIN_BACKOFF = Duration.ofSeconds(1);

    /** Upper bound of the SQS call retry delay. */
    public static final Duration DEFAULT_POLL_RETRY_MAX_BACKOFF = Duration.ofSeconds(30);

    /** Maximum visibility timeout accepted by SQS. */
    static final Duration MAX_VISIBILITY_TIMEOUT = Duration.ofHours(12);

    /** Largest exponent of the backoff; any larger receive count already saturates every sensible maximum. */
    private static final int MAX_BACKOFF_EXPONENT = 30;

    /**
     * Validates the settings.
     *
     * @throws NullPointerException     when a duration is {@code null}
     * @throws IllegalArgumentException when a value is out of range
     */
    public SqsListenerSettings {
        requireBetween(maxMessages, 1, 10, "maxMessages");
        requireBetween(waitTimeSeconds, 1, 20, "waitTimeSeconds");
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

    /**
     * Builds the settings from {@code ticketing.messaging.*} with the default backoffs.
     *
     * @param messaging       the messaging properties
     * @param shutdownTimeout drain budget ({@code spring.lifecycle.timeout-per-shutdown-phase})
     * @return the settings
     */
    public static SqsListenerSettings from(TicketingProperties.Messaging messaging, Duration shutdownTimeout) {
        Objects.requireNonNull(messaging, "messaging");
        return new SqsListenerSettings(messaging.maxMessages(), messaging.waitTimeSeconds(),
                messaging.visibilityTimeoutSeconds(), messaging.concurrency(), messaging.pollers(),
                DEFAULT_REDELIVERY_BASE_DELAY, DEFAULT_REDELIVERY_MAX_DELAY,
                DEFAULT_POLL_RETRY_MIN_BACKOFF, DEFAULT_POLL_RETRY_MAX_BACKOFF, shutdownTimeout);
    }

    /**
     * Delay before a failed message becomes visible again: {@code min(2^receiveCount x base, max)}.
     *
     * @param receiveCount how many times the message has been received (SQS {@code ApproximateReceiveCount})
     * @return the redelivery delay
     */
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
