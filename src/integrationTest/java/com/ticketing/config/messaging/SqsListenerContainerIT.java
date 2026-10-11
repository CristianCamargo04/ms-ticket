package com.ticketing.config.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketing.config.messaging.LocalStackSqsSupport.QueuePair;
import com.ticketing.config.messaging.SqsListenerMetrics.MessageOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Runs the {@link SqsListenerContainer} against a real SQS API (LocalStack 4.9): acknowledgement, redelivery with
 * an increasing receive count, dead-lettering through the redrive policy, and graceful shutdown.
 */
@Testcontainers
class SqsListenerContainerIT {

    private static final int MAX_RECEIVE_COUNT = 2;
    private static final int VISIBILITY_TIMEOUT_SECONDS = 5;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Short polls and backoffs: a failed message becomes visible again after 2^1 x 1s = 2s. */
    private static final SqsListenerSettings SETTINGS = new SqsListenerSettings(10, 1, VISIBILITY_TIMEOUT_SECONDS, 4,
            2, Duration.ofSeconds(1), Duration.ofSeconds(4), Duration.ofMillis(200), Duration.ofSeconds(1),
            Duration.ofSeconds(10));

    @Container
    private static final LocalStackContainer LOCALSTACK = LocalStackSqsSupport.localStack();

    private static SqsAsyncClient sqs;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private QueuePair queue;
    private SqsListenerContainer container;

    @BeforeAll
    static void createClient() {
        sqs = LocalStackSqsSupport.client(LOCALSTACK);
    }

    @AfterAll
    static void closeClient() {
        sqs.close();
    }

    @BeforeEach
    void createQueues() {
        String name = "listener-it-" + UUID.randomUUID().toString().substring(0, 8);
        queue = LocalStackSqsSupport.createQueueWithDeadLetterQueue(sqs, name, MAX_RECEIVE_COUNT,
                VISIBILITY_TIMEOUT_SECONDS);
    }

    @AfterEach
    void stopContainer() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void shouldDeleteTheMessageWhenTheHandlerCompletes() {
        List<String> handled = new CopyOnWriteArrayList<>();
        start(body -> Mono.deferContextual(context -> {
            handled.add(body + "|" + context.get("correlationId"));
            return Mono.empty();
        }));

        LocalStackSqsSupport.send(sqs, queue.url(), "order-1", "corr-it-1");

        await().atMost(TIMEOUT).until(() -> outcomes(MessageOutcome.ACKED) == 1.0);
        assertThat(handled).containsExactly("order-1|corr-it-1");
        await().atMost(TIMEOUT).until(() -> LocalStackSqsSupport.pendingMessages(sqs, queue.url()) == 0);
        // deleted, so never redelivered after the visibility timeout
        await().during(Duration.ofSeconds(VISIBILITY_TIMEOUT_SECONDS + 1)).atMost(TIMEOUT)
                .until(() -> handled.size() == 1);
        assertThat(LocalStackSqsSupport.receive(sqs, queue.dlqUrl())).isEmpty();
    }

    @Test
    void shouldRedeliverAFailingMessageWithAnIncreasingReceiveCountAndThenDeadLetterIt() {
        List<Integer> receiveCounts = new CopyOnWriteArrayList<>();
        start(body -> Mono.deferContextual(context -> {
            receiveCounts.add(context.get(SqsListenerContainer.RECEIVE_COUNT_KEY));
            return Mono.error(new IllegalStateException("payment gateway down"));
        }));

        LocalStackSqsSupport.send(sqs, queue.url(), "poison-pill", "corr-it-2");

        List<Message> deadLettered = new CopyOnWriteArrayList<>();
        await().atMost(TIMEOUT).until(() -> {
            deadLettered.addAll(LocalStackSqsSupport.receive(sqs, queue.dlqUrl()));
            return !deadLettered.isEmpty();
        });
        assertThat(deadLettered).extracting(Message::body).containsExactly("poison-pill");
        assertThat(receiveCounts).containsExactly(1, 2);
        assertThat(outcomes(MessageOutcome.FAILED)).isEqualTo(1.0);
        assertThat(outcomes(MessageOutcome.POISON)).isEqualTo(1.0);
        assertThat(outcomes(MessageOutcome.ACKED)).isZero();
        assertThat(LocalStackSqsSupport.pendingMessages(sqs, queue.url())).isZero();
    }

    @Test
    void shouldFinishTheInFlightMessageBeforeCompletingAGracefulStop() {
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean finishedBeforeStopCompleted = new AtomicBoolean();
        AtomicBoolean stopped = new AtomicBoolean();
        start(body -> Mono.fromRunnable(() -> started.set(true))
                .then(Mono.delay(Duration.ofSeconds(2)))
                .doOnNext(tick -> finished.set(true))
                .then());

        LocalStackSqsSupport.send(sqs, queue.url(), "slow-order", "corr-it-3");
        await().atMost(TIMEOUT).untilTrue(started);
        container.stop(() -> {
            finishedBeforeStopCompleted.set(finished.get());
            stopped.set(true);
        });

        assertThat(stopped).isFalse();
        assertThat(container.isRunning()).isTrue();
        await().atMost(TIMEOUT).untilTrue(stopped);
        assertThat(finishedBeforeStopCompleted).isTrue();
        assertThat(container.isRunning()).isFalse();
        assertThat(outcomes(MessageOutcome.ACKED)).isEqualTo(1.0);
        assertThat(LocalStackSqsSupport.pendingMessages(sqs, queue.url())).isZero();
    }

    private void start(Function<String, Mono<Void>> handler) {
        container = new SqsListenerContainer(sqs, List.of(new SqsQueueListener(queue.name(), handler)), SETTINGS,
                new SqsListenerMetrics(registry), Schedulers.parallel());
        container.start();
    }

    private double outcomes(MessageOutcome outcome) {
        Counter counter = registry.find(SqsListenerMetrics.MESSAGES)
                .tags(SqsListenerMetrics.QUEUE_TAG, queue.name(), SqsListenerMetrics.OUTCOME_TAG, outcome.tagValue())
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
