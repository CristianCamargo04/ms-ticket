package com.ticketing.config.messaging;

import com.ticketing.config.messaging.SqsListenerMetrics.MessageOutcome;
import com.ticketing.presentation.context.RequestContext;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.util.context.Context;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * Reactive SQS consumer that drives the inbound queue channels (ADR-004): a non-blocking replacement for Spring Cloud
 * AWS built on {@link SqsAsyncClient} and Reactor.
 *
 * <h2>Pipeline (per {@link SqsQueueListener})</h2>
 * <ol>
 *   <li>The queue URL is resolved with {@code GetQueueUrl}, retried with backoff until the queue exists. The
 *       {@code maxReceiveCount} of the queue's redrive policy is read once to recognize the last delivery.</li>
 *   <li>{@code pollers} receive loops call {@code ReceiveMessage} (long polling, {@code ApproximateReceiveCount} and
 *       every message attribute) and repeat while the container runs.</li>
 *   <li>Messages are processed by {@code flatMap(handle, concurrency)}: at most {@code concurrency} messages of a
 *       queue are in flight. The loops are merged with a prefetch of one, so a poller issues its next
 *       {@code ReceiveMessage} only once its previous batch has been handed to a free worker slot: received but not
 *       yet started messages never exceed {@code pollers x maxMessages}. Polling therefore follows the processing
 *       rate (backpressure) instead of piling up messages whose visibility timeout is ticking.</li>
 * </ol>
 *
 * <h2>Acknowledgement and redelivery</h2>
 * <ul>
 *   <li>Handler completion: the message is deleted (outcome {@code acked}).</li>
 *   <li>Handler error: the visibility timeout is changed to {@code min(2^receiveCount x 5s, 300s)}, so a failing
 *       message is redelivered with exponential backoff (outcome {@code failed}). On the last delivery allowed by the
 *       redrive policy the visibility is reset to zero instead, so SQS moves the message to the dead-letter queue
 *       right away (outcome {@code poison}, logged at ERROR).</li>
 *   <li>SQS call failures (queue resolution, polling) are retried with backoff forever and logged at WARN at most
 *       once per {@link #FAILURE_WARNING_INTERVAL}; one message never stops the pipeline (every per-message error is
 *       contained, no {@code onErrorContinue}).</li>
 * </ul>
 * The handler runs with the message's correlation id (message attribute {@value #CORRELATION_ID_ATTRIBUTE}, or the
 * SQS message id when absent or malformed), message id and receive count in its Reactor context; the container's
 * own log lines carry the correlation id in the MDC.
 *
 * <h2>Lifecycle</h2>
 * {@link #start()} subscribes the pipeline. {@link #stop(Runnable)} stops polling (an in-flight long poll is
 * cancelled), lets the in-flight and already received messages finish, then runs the callback; after
 * {@link SqsListenerSettings#shutdownTimeout()} the remaining handlers are cancelled (their messages are redelivered
 * after the visibility timeout). The {@link #PHASE phase} makes the container start after, and stop before, the web
 * server and its graceful shutdown, and long before the AWS SDK clients and the Reactor Netty resources are closed.
 * The only blocking code is the synchronous {@link #stop()} required by the {@code Lifecycle} contract.
 */
public final class SqsListenerContainer implements SmartLifecycle {

    /** Lifecycle phase: after the web server on start, before its graceful shutdown on stop. */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 512;

    /** Reactor context key of the SQS message id. */
    public static final String MESSAGE_ID_KEY = "sqsMessageId";

    /** Reactor context key of the SQS approximate receive count (an {@code Integer}). */
    public static final String RECEIVE_COUNT_KEY = "sqsReceiveCount";

    /** Message attribute carrying the correlation id (set by the publisher). */
    public static final String CORRELATION_ID_ATTRIBUTE = "correlationId";

    /** Requests every message attribute. */
    static final String ALL_MESSAGE_ATTRIBUTES = "All";

    /** Retries of a failed {@code DeleteMessage} before giving up (the message is then redelivered). */
    static final int DELETE_RETRIES = 2;

    /** First delay between two {@code DeleteMessage} attempts. */
    static final Duration DELETE_RETRY_BACKOFF = Duration.ofMillis(200);

    /** Minimum interval between two WARN logs of a failing SQS call loop. */
    static final Duration FAILURE_WARNING_INTERVAL = Duration.ofSeconds(30);

    /** Extra time the synchronous {@link #stop()} waits on top of the drain timeout. */
    static final Duration STOP_GRACE = Duration.ofSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(SqsListenerContainer.class);

    private static final String CORRELATION_ID_KEY = RequestContext.CORRELATION_ID_KEY;
    private static final Pattern VALID_CORRELATION_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Pattern MAX_RECEIVE_COUNT = Pattern.compile("\"maxReceiveCount\"\\s*:\\s*\"?(\\d+)\"?");
    private static final String UNKNOWN_MESSAGE_ID = "unknown";

    private final SqsAsyncClient sqsClient;
    private final List<SqsQueueListener> listeners;
    private final SqsListenerSettings settings;
    private final SqsListenerMetrics metrics;
    private final Scheduler scheduler;
    private final Object lifecycleMonitor = new Object();
    private final AtomicReference<Run> currentRun = new AtomicReference<>();

    /**
     * Creates a stopped container.
     *
     * @param sqsClient the asynchronous SQS client
     * @param listeners the queues to consume, at least one
     * @param settings  polling, concurrency, backoff and shutdown settings
     * @param metrics   the instrumentation
     * @param scheduler the scheduler of retry delays and of the shutdown timeout ({@code Schedulers.parallel()})
     */
    public SqsListenerContainer(SqsAsyncClient sqsClient, List<SqsQueueListener> listeners,
                                SqsListenerSettings settings, SqsListenerMetrics metrics, Scheduler scheduler) {
        this.sqsClient = Objects.requireNonNull(sqsClient, "sqsClient");
        this.listeners = List.copyOf(listeners);
        if (this.listeners.isEmpty()) {
            throw new IllegalArgumentException("at least one SQS queue listener is required");
        }
        this.settings = Objects.requireNonNull(settings, "settings");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /**
     * Whether the container consumes (or is still draining) messages.
     *
     * @return {@code true} from {@link #start()} until the stop has completed
     */
    @Override
    public boolean isRunning() {
        return currentRun.get() != null;
    }

    /**
     * Starts consuming every queue; does nothing when already running.
     */
    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (currentRun.get() != null) {
                return;
            }
            Run run = new Run();
            currentRun.set(run);
            log.info("Starting SQS listeners for {} (pollers={}, concurrency={}, maxMessages={})", queueNames(),
                    settings.pollers(), settings.concurrency(), settings.maxMessages());
            run.subscription.update(Flux.fromIterable(listeners)
                    .flatMap(listener -> consume(listener, run), listeners.size())
                    .then()
                    .doOnError(error -> log.error("SQS listener container failed", error))
                    .onErrorComplete()
                    .doFinally(signal -> terminated(run, signal))
                    .subscribe());
        }
    }

    /**
     * Stops polling, drains the in-flight messages and then runs the callback (asynchronously). In-flight handlers
     * still running after the shutdown timeout are cancelled.
     *
     * @param callback invoked once the container has stopped
     */
    @Override
    public void stop(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        Mono<Void> stopped;
        synchronized (lifecycleMonitor) {
            Run run = currentRun.get();
            stopped = run == null ? Mono.empty() : requestStop(run);
        }
        stopped.doFinally(signal -> callback.run()).subscribe();
    }

    /**
     * Synchronous variant of {@link #stop(Runnable)}.
     *
     * <p>This is the only blocking code of the container: {@code Lifecycle#stop()} must not return before the
     * component has stopped, so the calling thread waits (at most the shutdown timeout plus {@link #STOP_GRACE}).
     * Spring itself uses {@link #stop(Runnable)}.
     */
    @Override
    public void stop() {
        CountDownLatch stopped = new CountDownLatch(1);
        stop(stopped::countDown);
        try {
            if (!stopped.await(settings.shutdownTimeout().plus(STOP_GRACE).toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("SQS listeners are still stopping after {}", settings.shutdownTimeout().plus(STOP_GRACE));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for the SQS listeners to stop");
        }
    }

    /**
     * Parses the {@code maxReceiveCount} of a redrive policy.
     *
     * @param redrivePolicy the {@code RedrivePolicy} queue attribute (JSON), {@code null} when the queue has none
     * @return the maximum receive count, empty when there is no (parsable) redrive policy
     * @throws NumberFormatException when the count does not fit in an {@code int}
     */
    static OptionalInt parseMaxReceiveCount(String redrivePolicy) {
        if (redrivePolicy == null) {
            return OptionalInt.empty();
        }
        Matcher matcher = MAX_RECEIVE_COUNT.matcher(redrivePolicy);
        return matcher.find() ? OptionalInt.of(Integer.parseInt(matcher.group(1))) : OptionalInt.empty();
    }

    private Mono<Void> requestStop(Run run) {
        if (run.completion == null) {
            log.info("Stopping SQS listeners: polling stopped, draining {} in-flight message(s) within {}",
                    inFlightMessages(), settings.shutdownTimeout());
            run.stopping = true;
            run.stopSignal.tryEmitValue(Boolean.TRUE);
            run.completion = run.terminated.asMono()
                    .timeout(settings.shutdownTimeout(), scheduler)
                    .onErrorResume(TimeoutException.class, timeout -> forceStop(run))
                    .cache();
        }
        return run.completion;
    }

    private Mono<Void> forceStop(Run run) {
        log.warn("SQS listeners did not drain within {}: cancelling {} in-flight message(s), redelivered after their "
                + "visibility timeout", settings.shutdownTimeout(), inFlightMessages());
        run.subscription.dispose();
        return Mono.empty();
    }

    private void terminated(Run run, SignalType signal) {
        currentRun.compareAndSet(run, null);
        if (run.stopping) {
            log.info("SQS listeners stopped ({})", signal);
        } else {
            log.error("SQS listeners terminated unexpectedly ({}): no message is consumed until restart", signal);
        }
        run.terminated.tryEmitEmpty();
    }

    private Mono<Void> consume(SqsQueueListener listener, Run run) {
        return resolveQueue(listener)
                .takeUntilOther(run.stopSignal.asMono())
                .flatMapMany(queue -> receive(queue, run)
                        .flatMap(message -> process(queue, message), settings.concurrency()))
                .then();
    }

    private Mono<QueueBinding> resolveQueue(SqsQueueListener listener) {
        String queueName = listener.queueName();
        FailureLog failures = new FailureLog("resolve the URL of", queueName);
        return Mono.fromFuture(() -> sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()))
                .retryWhen(retryForever(failures))
                .doOnNext(response -> failures.recovered())
                .flatMap(response -> maxReceiveCount(queueName, response.queueUrl())
                        .map(maxReceiveCount -> new QueueBinding(queueName, response.queueUrl(), maxReceiveCount,
                                listener.handler(), metrics.inFlight(queueName))))
                .doOnNext(SqsListenerContainer::logResolved);
    }

    private Mono<OptionalInt> maxReceiveCount(String queueName, String queueUrl) {
        return Mono.fromFuture(() -> sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.REDRIVE_POLICY)
                        .build()))
                .map(response -> parseMaxReceiveCount(response.attributes().get(QueueAttributeName.REDRIVE_POLICY)))
                .onErrorResume(error -> {
                    log.warn("Cannot read the redrive policy of SQS queue {}: dead-letter detection is disabled ({})",
                            queueName, error.toString());
                    return Mono.just(OptionalInt.empty());
                });
    }

    private static void logResolved(QueueBinding queue) {
        if (queue.maxReceiveCount().isPresent()) {
            log.info("Consuming SQS queue {} (dead-lettered after {} receives)", queue.name(),
                    queue.maxReceiveCount().getAsInt());
        } else {
            log.warn("SQS queue {} has no redrive policy: a message that keeps failing is redelivered until it expires",
                    queue.name());
        }
    }

    private Flux<Message> receive(QueueBinding queue, Run run) {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queue.url())
                .maxNumberOfMessages(settings.maxMessages())
                .waitTimeSeconds(settings.waitTimeSeconds())
                .visibilityTimeout(settings.visibilityTimeoutSeconds())
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                .messageAttributeNames(ALL_MESSAGE_ATTRIBUTES)
                .build();
        FailureLog failures = new FailureLog("receive messages from", queue.name());
        Flux<Message> poller = Mono.fromFuture(() -> sqsClient.receiveMessage(request))
                .retryWhen(retryForever(failures))
                .doOnNext(response -> failures.recovered())
                .takeUntilOther(run.stopSignal.asMono())
                .flatMapIterable(ReceiveMessageResponse::messages)
                .repeat(() -> !run.stopping);
        return Flux.range(0, settings.pollers()).flatMap(_ -> poller, settings.pollers(), 1);
    }

    private Mono<Void> process(QueueBinding queue, Message message) {
        return Mono.defer(() -> {
                    Delivery delivery = Delivery.of(message);
                    Timer.Sample sample = metrics.startProcessing();
                    queue.inFlight().incrementAndGet();
                    return handle(queue, delivery)
                            .then(Mono.defer(() -> acknowledge(queue, delivery)))
                            .onErrorResume(error -> reject(queue, delivery, error))
                            .doOnNext(outcome -> metrics.record(queue.name(), outcome, sample))
                            .doFinally(signal -> queue.inFlight().decrementAndGet());
                })
                .onErrorResume(error -> {
                    log.error("Unexpected failure while processing message {} of SQS queue {}", message.messageId(),
                            queue.name(), error);
                    return Mono.empty();
                })
                .then();
    }

    private static Mono<Void> handle(QueueBinding queue, Delivery delivery) {
        return Mono.defer(() -> queue.handler().apply(delivery.message().body()))
                .contextWrite(Context.of(CORRELATION_ID_KEY, delivery.correlationId(),
                        MESSAGE_ID_KEY, delivery.messageId(),
                        RECEIVE_COUNT_KEY, delivery.receiveCount()));
    }

    private Mono<MessageOutcome> acknowledge(QueueBinding queue, Delivery delivery) {
        return Mono.fromFuture(() -> sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queue.url())
                        .receiptHandle(delivery.message().receiptHandle())
                        .build()))
                .retryWhen(Retry.backoff(DELETE_RETRIES, DELETE_RETRY_BACKOFF)
                        .scheduler(scheduler)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .thenReturn(MessageOutcome.ACKED)
                .onErrorResume(error -> {
                    withCorrelationId(delivery, () -> log.warn("Message {} of SQS queue {} was processed but cannot be "
                            + "deleted; the idempotent consumer will ignore its redelivery: {}", delivery.messageId(),
                            queue.name(), error.toString()));
                    return Mono.just(MessageOutcome.FAILED);
                });
    }

    private Mono<MessageOutcome> reject(QueueBinding queue, Delivery delivery, Throwable error) {
        OptionalInt maxReceiveCount = queue.maxReceiveCount();
        boolean poison = maxReceiveCount.isPresent() && delivery.receiveCount() >= maxReceiveCount.getAsInt();
        Duration redeliveryDelay = poison ? Duration.ZERO : settings.redeliveryDelay(delivery.receiveCount());
        MessageOutcome outcome = poison ? MessageOutcome.POISON : MessageOutcome.FAILED;
        withCorrelationId(delivery, () -> {
            if (poison) {
                log.error("Message {} of SQS queue {} failed on its last delivery ({} of {}): moving it to the "
                        + "dead-letter queue", delivery.messageId(), queue.name(), delivery.receiveCount(),
                        maxReceiveCount.getAsInt(), error);
            } else {
                log.warn("Message {} of SQS queue {} failed on delivery {}; redelivery in {}: {}", delivery.messageId(),
                        queue.name(), delivery.receiveCount(), redeliveryDelay, error.toString());
                log.debug("Failure of message {}", delivery.messageId(), error);
            }
        });
        return Mono.fromFuture(() -> sqsClient.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                        .queueUrl(queue.url())
                        .receiptHandle(delivery.message().receiptHandle())
                        .visibilityTimeout(Math.toIntExact(redeliveryDelay.toSeconds()))
                        .build()))
                .thenReturn(outcome)
                .onErrorResume(visibilityError -> {
                    withCorrelationId(delivery, () -> log.warn("Cannot change the visibility of message {} of SQS "
                            + "queue {}; it is redelivered after its current visibility timeout: {}",
                            delivery.messageId(), queue.name(), visibilityError.toString()));
                    return Mono.just(outcome);
                });
    }

    private RetryBackoffSpec retryForever(FailureLog failures) {
        return Retry.backoff(Long.MAX_VALUE, settings.pollRetryMinBackoff())
                .maxBackoff(settings.pollRetryMaxBackoff())
                .scheduler(scheduler)
                .doBeforeRetry(signal -> failures.failed(signal.failure()));
    }

    private List<String> queueNames() {
        return listeners.stream().map(SqsQueueListener::queueName).toList();
    }

    private int inFlightMessages() {
        return listeners.stream().mapToInt(listener -> metrics.inFlight(listener.queueName()).get()).sum();
    }

    private static void withCorrelationId(Delivery delivery, Runnable logStatement) {
        try (MDC.MDCCloseable _ = MDC.putCloseable(CORRELATION_ID_KEY, delivery.correlationId())) {
            logStatement.run();
        }
    }

    /** State of one start/stop cycle. */
    private static final class Run {
        private final Sinks.One<Boolean> stopSignal = Sinks.one();
        private final Sinks.Empty<Void> terminated = Sinks.empty();
        private final Disposable.Swap subscription = Disposables.swap();
        private volatile boolean stopping;
        /** Completion of the stop request; guarded by the lifecycle monitor. */
        private Mono<Void> completion;
    }

    /** A resolved queue and its handler. */
    private record QueueBinding(String name, String url, OptionalInt maxReceiveCount,
                                Function<String, Mono<Void>> handler, AtomicInteger inFlight) {
    }

    /** One received message with its parsed metadata. */
    private record Delivery(Message message, String messageId, String correlationId, int receiveCount) {

        static Delivery of(Message message) {
            String messageId = Objects.requireNonNullElse(message.messageId(), UNKNOWN_MESSAGE_ID);
            MessageAttributeValue attribute = message.messageAttributes().get(CORRELATION_ID_ATTRIBUTE);
            String candidate = attribute == null ? null : attribute.stringValue();
            String correlationId = candidate != null && VALID_CORRELATION_ID.matcher(candidate).matches()
                    ? candidate
                    : messageId;
            return new Delivery(message, messageId, correlationId, receiveCount(message));
        }

        private static int receiveCount(Message message) {
            String value = message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
            if (value == null) {
                return 1;
            }
            try {
                return Math.max(1, Integer.parseInt(value));
            } catch (NumberFormatException malformed) {
                return 1;
            }
        }
    }

    /** Rate-limited logging of a failing SQS call loop (shared by the pollers of a queue). */
    private final class FailureLog {
        private static final long NEVER = Long.MIN_VALUE;

        private final String action;
        private final String queueName;
        private long consecutiveFailures;
        private long suppressedWarnings;
        private long lastWarningAt = NEVER;

        FailureLog(String action, String queueName) {
            this.action = action;
            this.queueName = queueName;
        }

        synchronized void failed(Throwable error) {
            consecutiveFailures++;
            long now = scheduler.now(TimeUnit.MILLISECONDS);
            if (lastWarningAt == NEVER || now - lastWarningAt >= FAILURE_WARNING_INTERVAL.toMillis()) {
                log.warn("Cannot {} SQS queue {} ({} consecutive failure(s), {} warning(s) suppressed); retrying with "
                        + "backoff: {}", action, queueName, consecutiveFailures, suppressedWarnings, error.toString());
                lastWarningAt = now;
                suppressedWarnings = 0;
            } else {
                suppressedWarnings++;
                log.debug("Cannot {} SQS queue {} ({} consecutive failure(s)): {}", action, queueName,
                        consecutiveFailures, error.toString());
            }
        }

        synchronized void recovered() {
            if (consecutiveFailures > 0) {
                log.info("Can {} SQS queue {} again after {} failure(s)", action, queueName, consecutiveFailures);
                consecutiveFailures = 0;
                suppressedWarnings = 0;
                lastWarningAt = NEVER;
            }
        }
    }
}
