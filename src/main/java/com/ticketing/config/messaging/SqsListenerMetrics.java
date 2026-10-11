package com.ticketing.config.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class SqsListenerMetrics {
    public static final String MESSAGES = "sqs.messages";

    public static final String PROCESSING = "sqs.message.processing";

    public static final String IN_FLIGHT = "sqs.messages.in.flight";

    public static final String QUEUE_TAG = "queue";

    public static final String OUTCOME_TAG = "outcome";

    private final MeterRegistry registry;
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    public enum MessageOutcome {
        ACKED,

        FAILED,

        POISON;

        public String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public Timer.Sample startProcessing() {
        return Timer.start(registry);
    }

    public void record(String queueName, MessageOutcome outcome, Timer.Sample sample) {
        Tags tags = Tags.of(QUEUE_TAG, queueName, OUTCOME_TAG, outcome.tagValue());
        registry.counter(MESSAGES, tags).increment();
        sample.stop(registry.timer(PROCESSING, tags));
    }

    public AtomicInteger inFlight(String queueName) {
        return inFlight.computeIfAbsent(queueName, name -> {
            AtomicInteger counter = new AtomicInteger();
            registry.gauge(IN_FLIGHT, Tags.of(QUEUE_TAG, name), counter);
            return counter;
        });
    }
}
