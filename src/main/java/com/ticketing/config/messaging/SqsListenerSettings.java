package com.ticketing.config.messaging;

import com.ticketing.config.properties.TicketingProperties;
import java.time.Duration;
import java.util.Objects;

/**
 * Runtime settings of the {@link SqsListenerContainer}.
 *
 * <p>{@code waitTimeSeconds} accepts the whole range documented for {@code ticketing.messaging.wait-time-seconds}
 * ({@code 0..20}). Long polling ({@code >= 1}) is strongly recommended; with short polling ({@code 0}) the container
 * pauses {@link SqsListenerContainer#SHORT_POLLING_IDLE_DELAY} after every empty receive, so an idle queue never turns
 * a poller into a hot loop of empty {@code ReceiveMessage} calls.
 *
 * @param maxMessages              messages per {@code ReceiveMessage} call (1..10)
 * @param waitTimeSeconds          long polling wait (0..20, 0 = short polling)
 * @param visibilityTimeoutSeconds visibility timeout requested for received messages (1..43200); must exceed the
 *                                 longest expected processing time, a handler is cancelled after
 *                                 {@link #handlerTimeout()}
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

    /** Share of a bound kept in reserve: the handler and drain timeouts use 90 % of their bound. */
    private static final int RESERVE_DIVISOR = 10;

    /**
     * Validates the settings.
     *
     * @throws NullPointerException     when a duration is {@code null}
     * @throws IllegalArgumentException when a value is out of range
     */
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

    /**
     * Builds the settings from {@code ticketing.messaging.*} with the default backoffs.
     *
     * <p>The drain budget is {@link #drainBudget(Duration) 90 %} of the lifecycle phase timeout: Spring waits at most
     * the phase timeout for the container, so the forced cancellation of the handlers still running after the budget
     * has to complete inside that window.
     *
     * @param messaging    the messaging properties
     * @param phaseTimeout graceful shutdown budget of the lifecycle phase
     *                     ({@code spring.lifecycle.timeout-per-shutdown-phase})
     * @return the settings
     */
    public static SqsListenerSettings from(TicketingProperties.Messaging messaging, Duration phaseTimeout) {
        Objects.requireNonNull(messaging, "messaging");
        return new SqsListenerSettings(messaging.maxMessages(), messaging.waitTimeSeconds(),
                messaging.visibilityTimeoutSeconds(), messaging.concurrency(), messaging.pollers(),
                DEFAULT_REDELIVERY_BASE_DELAY, DEFAULT_REDELIVERY_MAX_DELAY,
                DEFAULT_POLL_RETRY_MIN_BACKOFF, DEFAULT_POLL_RETRY_MAX_BACKOFF, drainBudget(phaseTimeout));
    }

    /**
     * Time the container may spend draining within a lifecycle phase: 90 % of the phase timeout, the rest being kept
     * for the forced cancellation and the stop callback.
     *
     * @param phaseTimeout the lifecycle phase timeout
     * @return the drain budget
     */
    static Duration drainBudget(Duration phaseTimeout) {
        Objects.requireNonNull(phaseTimeout, "phaseTimeout");
        return phaseTimeout.minus(phaseTimeout.dividedBy(RESERVE_DIVISOR));
    }

    /**
     * Maximum processing time of one message: 90 % of the visibility timeout. A handler still running then is
     * cancelled and the message is rejected (redelivery backoff), which frees its worker slot before SQS makes the
     * message visible to another consumer.
     *
     * @return the handler timeout
     */
    public Duration handlerTimeout() {
        Duration visibilityTimeout = Duration.ofSeconds(visibilityTimeoutSeconds);
        return visibilityTimeout.minus(visibilityTimeout.dividedBy(RESERVE_DIVISOR));
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
