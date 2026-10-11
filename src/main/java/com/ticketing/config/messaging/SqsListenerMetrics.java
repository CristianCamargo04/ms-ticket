package com.ticketing.config.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer instrumentation of the {@link SqsListenerContainer}.
 *
 * <ul>
 *   <li>{@code sqs.messages{queue, outcome}} — counter of processed messages by {@link MessageOutcome};</li>
 *   <li>{@code sqs.message.processing{queue, outcome}} — timer from the start of the handler to the
 *       acknowledgement or the redelivery scheduling;</li>
 *   <li>{@code sqs.messages.in.flight{queue}} — gauge of messages currently being processed.</li>
 * </ul>
 */
public class SqsListenerMetrics {

    /** Counter of processed messages. */
    public static final String MESSAGES = "sqs.messages";

    /** Timer of the processing of one message. */
    public static final String PROCESSING = "sqs.message.processing";

    /** Gauge of the messages being processed. */
    public static final String IN_FLIGHT = "sqs.messages.in.flight";

    /** Tag holding the queue name. */
    public static final String QUEUE_TAG = "queue";

    /** Tag holding the {@link MessageOutcome}. */
    public static final String OUTCOME_TAG = "outcome";

    private final MeterRegistry registry;
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    /**
     * Final processing outcome of a received message.
     */
    public enum MessageOutcome {
        /** The handler completed and the message was deleted. */
        ACKED,
        /** The handler failed (or the deletion failed): the message will be redelivered. */
        FAILED,
        /** The handler failed on the last delivery allowed by the redrive policy: the message is dead-lettered. */
        POISON;

        /**
         * Value of the {@code outcome} tag.
         *
         * @return the lower-case name
         */
        public String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Creates the instrumentation.
     *
     * @param registry the meter registry
     */
    public SqsListenerMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Starts timing the processing of a message.
     *
     * @return the running sample, to be passed to {@link #record}
     */
    public Timer.Sample startProcessing() {
        return Timer.start(registry);
    }

    /**
     * Records the outcome and the duration of the processing of one message.
     *
     * @param queueName the queue
     * @param outcome   the outcome
     * @param sample    the sample started by {@link #startProcessing()}
     */
    public void record(String queueName, MessageOutcome outcome, Timer.Sample sample) {
        Tags tags = Tags.of(QUEUE_TAG, queueName, OUTCOME_TAG, outcome.tagValue());
        registry.counter(MESSAGES, tags).increment();
        sample.stop(registry.timer(PROCESSING, tags));
    }

    /**
     * Counter of the messages being processed for a queue, registered as a gauge on first use. The same instance is
     * returned on every call (and across container restarts), so the gauge always tracks the live counter.
     *
     * @param queueName the queue
     * @return the in-flight counter of the queue
     */
    public AtomicInteger inFlight(String queueName) {
        return inFlight.computeIfAbsent(queueName, name -> {
            AtomicInteger counter = new AtomicInteger();
            registry.gauge(IN_FLIGHT, Tags.of(QUEUE_TAG, name), counter);
            return counter;
        });
    }
}
