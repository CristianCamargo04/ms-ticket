package com.ticketing.config.messaging;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ticketing.config.messaging.SqsListenerMetrics.MessageOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

@ExtendWith(MockitoExtension.class)
class SqsListenerContainerTest {

    private static final String QUEUE = "orders";
    private static final String QUEUE_URL = "http://sqs.local/000000000000/orders";
    private static final String REDRIVE_POLICY = "{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:0:orders-dlq\","
            + "\"maxReceiveCount\":\"3\"}";
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    private static final SqsListenerSettings SETTINGS = settings(4, 1, Duration.ofSeconds(30), SHUTDOWN_TIMEOUT);

    @Mock
    private SqsAsyncClient sqs;

    private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SqsListenerMetrics metrics = new SqsListenerMetrics(registry);
    private final Queue<CompletableFuture<ReceiveMessageResponse>> scriptedPolls = new ConcurrentLinkedQueue<>();
    private final List<CompletableFuture<ReceiveMessageResponse>> pendingPolls = new CopyOnWriteArrayList<>();
    private final List<SqsListenerContainer> containers = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger containerLogger = (Logger) LoggerFactory.getLogger(SqsListenerContainer.class);

    @BeforeEach
    void captureLogs() {
        logs.start();
        containerLogger.addAppender(logs);
        containerLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void stopContainers() {
        containers.forEach(container -> container.stop(() -> { }));
        scheduler.advanceTimeBy(Duration.ofMinutes(5));
        scheduler.dispose();
        containerLogger.detachAppender(logs);
        containerLogger.setLevel(null);
    }

    @Test
    void shouldDeleteMessagesWhenHandlerCompletes() {
        givenQueue(REDRIVE_POLICY);
        givenPolls(batch(message("m1", 1), message("m2", 1)));
        givenDeleteSucceeds();
        List<String> bodies = new CopyOnWriteArrayList<>();

        start(container(body -> Mono.fromRunnable(() -> bodies.add(body))));

        assertThat(bodies).containsExactly("body-m1", "body-m2");
        ArgumentCaptor<DeleteMessageRequest> deletes = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqs, times(2)).deleteMessage(deletes.capture());
        assertThat(deletes.getAllValues()).extracting(DeleteMessageRequest::receiptHandle)
                .containsExactly("receipt-m1", "receipt-m2");
        assertThat(deletes.getAllValues()).extracting(DeleteMessageRequest::queueUrl).containsOnly(QUEUE_URL);
        verify(sqs, never()).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
        assertThat(messages(MessageOutcome.ACKED)).isEqualTo(2.0);
        assertThat(registry.get(SqsListenerMetrics.PROCESSING).tag("outcome", "acked").timer().count()).isEqualTo(2);
    }

    @Test
    void shouldReceiveWithLongPollingReceiveCountAndAllMessageAttributes() {
        givenQueue(REDRIVE_POLICY);
        givenPolls();

        start(container(body -> Mono.empty()));

        ArgumentCaptor<ReceiveMessageRequest> receive = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(sqs).receiveMessage(receive.capture());
        ReceiveMessageRequest request = receive.getValue();
        assertThat(request.queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(request.maxNumberOfMessages()).isEqualTo(10);
        assertThat(request.waitTimeSeconds()).isEqualTo(20);
        assertThat(request.visibilityTimeout()).isEqualTo(60);
        assertThat(request.messageSystemAttributeNames())
                .containsExactly(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
        assertThat(request.messageAttributeNames()).containsExactly("All");
        ArgumentCaptor<GetQueueUrlRequest> queueUrl = ArgumentCaptor.forClass(GetQueueUrlRequest.class);
        verify(sqs).getQueueUrl(queueUrl.capture());
        assertThat(queueUrl.getValue().queueName()).isEqualTo(QUEUE);
        ArgumentCaptor<GetQueueAttributesRequest> attributes = ArgumentCaptor.forClass(GetQueueAttributesRequest.class);
        verify(sqs).getQueueAttributes(attributes.capture());
        assertThat(attributes.getValue().attributeNames()).containsExactly(QueueAttributeName.REDRIVE_POLICY);
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("Consuming SQS queue orders (dead-lettered after 3 receives)"));
    }

    @Test
    void shouldExposeCorrelationIdMessageIdAndReceiveCountInHandlerContext() {
        givenQueue(REDRIVE_POLICY);
        Message withCorrelation = message("m1", 2);
        Message withoutCorrelation = Message.builder().messageId("m2").receiptHandle("receipt-m2").body("b").build();
        Message withMalformedCorrelation = withCorrelation.toBuilder().messageId("m3")
                .messageAttributes(Map.of(SqsListenerContainer.CORRELATION_ID_ATTRIBUTE, stringAttribute("bad id!")))
                .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "not-a-number"))
                .build();
        Message withBinaryCorrelation = withCorrelation.toBuilder().messageId(null)
                .messageAttributes(Map.of(SqsListenerContainer.CORRELATION_ID_ATTRIBUTE,
                        MessageAttributeValue.builder().dataType("Binary").build()))
                .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "0"))
                .build();
        givenPolls(batch(withCorrelation, withoutCorrelation, withMalformedCorrelation, withBinaryCorrelation));
        givenDeleteSucceeds();
        List<String> contexts = new CopyOnWriteArrayList<>();

        start(container(body -> Mono.deferContextual(context -> {
            contexts.add(context.get("correlationId") + "|" + context.get(SqsListenerContainer.MESSAGE_ID_KEY) + "|"
                    + context.get(SqsListenerContainer.RECEIVE_COUNT_KEY));
            return Mono.empty();
        })));

        assertThat(contexts).containsExactly("corr-m1|m1|2", "m2|m2|1", "m3|m3|1", "unknown|unknown|1");
    }

    @ParameterizedTest(name = "receive count {0} -> redelivery in {1}s")
    @CsvSource({"1, 10", "2, 20", "3, 40", "4, 80", "5, 160", "6, 300", "7, 300", "40, 300"})
    void shouldDelayRedeliveryExponentiallyWhenHandlerFails(int receiveCount, int expectedSeconds) {
        givenQueue(null);
        givenPolls(batch(message("m1", receiveCount)));
        givenVisibilityChangeSucceeds();

        start(container(body -> Mono.error(new IllegalStateException("boom"))));

        ArgumentCaptor<ChangeMessageVisibilityRequest> change =
                ArgumentCaptor.forClass(ChangeMessageVisibilityRequest.class);
        verify(sqs).changeMessageVisibility(change.capture());
        assertThat(change.getValue().visibilityTimeout()).isEqualTo(expectedSeconds);
        assertThat(change.getValue().receiptHandle()).isEqualTo("receipt-m1");
        assertThat(change.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
        assertThat(messages(MessageOutcome.FAILED)).isEqualTo(1.0);
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("orders has no redrive policy"));
    }

    @Test
    void shouldLogFailuresWithTheCorrelationIdInTheMdc() {
        givenQueue(REDRIVE_POLICY);
        givenPolls(batch(message("m1", 1)));
        givenVisibilityChangeSucceeds();

        start(container(body -> Mono.error(new IllegalStateException("boom"))));

        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN).singleElement()
                .satisfies(event -> {
                    assertThat(event.getFormattedMessage())
                            .contains("Message m1 of SQS queue orders failed on delivery 1; redelivery in PT10S")
                            .contains("boom");
                    assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", "corr-m1");
                });
    }

    @Test
    void shouldKeepTheRedeliveryBackoffOnTheLastAllowedDeliveryAndCountItAsPoison() {
        givenQueue(REDRIVE_POLICY);
        // m3 exceeds the startup maxReceiveCount (e.g. the redrive policy was raised or removed meanwhile)
        givenPolls(batch(message("m1", 2), message("m2", 3), message("m3", 7)));
        givenVisibilityChangeSucceeds();

        start(container(body -> Mono.error(new IllegalStateException("boom"))));

        ArgumentCaptor<ChangeMessageVisibilityRequest> changes =
                ArgumentCaptor.forClass(ChangeMessageVisibilityRequest.class);
        verify(sqs, times(3)).changeMessageVisibility(changes.capture());
        assertThat(changes.getAllValues()).extracting(ChangeMessageVisibilityRequest::visibilityTimeout)
                .containsExactly(20, 40, 300);
        assertThat(messages(MessageOutcome.FAILED)).isEqualTo(1.0);
        assertThat(messages(MessageOutcome.POISON)).isEqualTo(2.0);
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.ERROR)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Message m2 of SQS queue orders failed on its last delivery allowed by the redrive "
                                + "policy (3 of 3): SQS moves it to the dead-letter queue on its next receive, in PT40S",
                        "Message m3 of SQS queue orders failed on its last delivery allowed by the redrive "
                                + "policy (7 of 3): SQS moves it to the dead-letter queue on its next receive, in PT5M");
    }

    @Test
    void shouldTimeOutAHungHandlerRejectItsMessageAndReleaseItsWorkerSlot() {
        givenQueue(REDRIVE_POLICY);
        givenPolls(batch(message("m1", 1), message("m2", 1)));
        givenVisibilityChangeSucceeds();
        givenDeleteSucceeds();
        AtomicBoolean cancelled = new AtomicBoolean();
        List<String> completed = new CopyOnWriteArrayList<>();
        SqsListenerSettings settings = settings(1, 1, Duration.ofSeconds(30), SHUTDOWN_TIMEOUT);
        Duration handlerTimeout = settings.handlerTimeout();

        start(container(settings, body -> body.equals("body-m1")
                ? Mono.<Void>never().doOnCancel(() -> cancelled.set(true))
                : Mono.fromRunnable(() -> completed.add(body))));
        scheduler.advanceTimeBy(handlerTimeout.minusMillis(1));

        assertThat(handlerTimeout).isEqualTo(Duration.ofSeconds(54));
        assertThat(completed).isEmpty();
        assertThat(registry.get(SqsListenerMetrics.IN_FLIGHT).gauge().value()).isEqualTo(1.0);
        verify(sqs, never()).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));

        scheduler.advanceTimeBy(Duration.ofMillis(1));

        assertThat(cancelled).isTrue();
        ArgumentCaptor<ChangeMessageVisibilityRequest> change =
                ArgumentCaptor.forClass(ChangeMessageVisibilityRequest.class);
        verify(sqs).changeMessageVisibility(change.capture());
        assertThat(change.getValue().receiptHandle()).isEqualTo("receipt-m1");
        assertThat(change.getValue().visibilityTimeout()).isEqualTo(10);
        assertThat(completed).containsExactly("body-m2");
        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
        assertThat(messages(MessageOutcome.FAILED)).isEqualTo(1.0);
        assertThat(messages(MessageOutcome.ACKED)).isEqualTo(1.0);
        assertThat(registry.get(SqsListenerMetrics.IN_FLIGHT).gauge().value()).isZero();
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN).singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("Message m1 of SQS queue orders failed on delivery 1; redelivery in PT10S")
                        .contains("handler did not complete within PT54S"));
    }

    @Test
    void shouldTreatHandlerThatThrowsOrReturnsNullAsFailure() {
        givenQueue(REDRIVE_POLICY);
        givenPolls(batch(message("m1", 1), message("m2", 1)));
        givenVisibilityChangeSucceeds();

        start(container(body -> {
            if (body.equals("body-m1")) {
                throw new IllegalStateException("synchronous failure");
            }
            return null;
        }));

        verify(sqs, times(2)).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
        assertThat(messages(MessageOutcome.FAILED)).isEqualTo(2.0);
    }

    @Test
    void shouldKeepConsumingWhenVisibilityChangeFails() {
        givenQueue(REDRIVE_POLICY);
        givenPolls(batch(message("m1", 1), message("m2", 1)));
        when(sqs.changeMessageVisibility(any(ChangeMessageVisibilityRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("receipt handle expired")));
        givenDeleteSucceeds();

        start(container(body -> body.equals("body-m1") ? Mono.error(new IllegalStateException("boom")) : Mono.empty()));

        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
        assertThat(messages(MessageOutcome.FAILED)).isEqualTo(1.0);
        assertThat(messages(MessageOutcome.ACKED)).isEqualTo(1.0);
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("Cannot change the visibility of message m1"));
    }

    @Test
    void shouldRetryDeletionBeforeAcknowledging() {
        givenQueue(REDRIVE_POLICY);
        givenPolls(batch(message("m1", 1)));
        when(sqs.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("connection reset")))
                .thenReturn(completedFuture(DeleteMessageResponse.builder().build()));

        start(container(body -> Mono.empty()));
        assertThat(messages(MessageOutcome.ACKED)).isZero();
        scheduler.advanceTimeBy(Duration.ofSeconds(1));

        verify(sqs, times(2)).deleteMessage(any(DeleteMessageRequest.class));
        assertThat(messages(MessageOutcome.ACKED)).isEqualTo(1.0);
    }

    @Test
    void shouldCountFailureWhenDeletionKeepsFailing() {
        givenQueue(REDRIVE_POLICY);
        givenPolls(batch(message("m1", 1)));
        when(sqs.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("connection reset")));

        start(container(body -> Mono.empty()));
        scheduler.advanceTimeBy(Duration.ofSeconds(5));

        verify(sqs, times(1 + SqsListenerContainer.DELETE_RETRIES)).deleteMessage(any(DeleteMessageRequest.class));
        assertThat(messages(MessageOutcome.FAILED)).isEqualTo(1.0);
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("Message m1 of SQS queue orders was processed but cannot be deleted")
                .contains("connection reset"));
    }

    @Test
    void shouldRetryPollingWithBackoffWhenSqsIsUnavailable() {
        givenQueue(REDRIVE_POLICY);
        givenPolls(failure(SdkClientException.create("connection refused")),
                failure(SdkClientException.create("connection refused")),
                batch(message("m1", 1)));
        givenDeleteSucceeds();
        List<String> bodies = new CopyOnWriteArrayList<>();

        start(container(body -> Mono.fromRunnable(() -> bodies.add(body))));
        assertThat(bodies).isEmpty();
        scheduler.advanceTimeBy(Duration.ofSeconds(5));

        assertThat(bodies).containsExactly("body-m1");
        verify(sqs, times(4)).receiveMessage(any(ReceiveMessageRequest.class));
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN).singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("Cannot receive messages from SQS queue orders (1 consecutive failure(s), "
                                + "0 warning(s) suppressed)"));
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("Can receive messages from SQS queue orders again after 2 failure(s)"));
    }

    @Test
    void shouldRateLimitWarningsWhileSqsStaysUnavailable() {
        givenQueue(REDRIVE_POLICY);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("connection refused")));
        SqsListenerSettings settings = new SqsListenerSettings(10, 20, 60, 4, 1, Duration.ofSeconds(5),
                Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofSeconds(1), SHUTDOWN_TIMEOUT);

        start(container(settings, body -> Mono.empty()));
        scheduler.advanceTimeBy(Duration.ofSeconds(119));

        verify(sqs, times(120)).receiveMessage(any(ReceiveMessageRequest.class));
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .hasSize(4)
                .allSatisfy(message -> assertThat(message).contains("connection refused"))
                .last().asString().contains("29 warning(s) suppressed");
    }

    @Test
    void shouldRateLimitWarningsWhenFailuresAlternateWithSuccesses() {
        givenQueue(REDRIVE_POLICY);
        List<CompletableFuture<ReceiveMessageResponse>> flakyPolls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            flakyPolls.add(failure(SdkClientException.create("throttled")));
            flakyPolls.add(batch());
        }
        scriptedPolls.addAll(flakyPolls);
        givenPolls();

        start(container(body -> Mono.empty()));
        // each failure is retried after 1 to 1.5 s, so the 20 failures all happen within the first 30 s
        scheduler.advanceTimeBy(Duration.ofSeconds(60));

        verify(sqs, times(41)).receiveMessage(any(ReceiveMessageRequest.class));
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN).singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("Cannot receive messages from SQS queue orders (1 consecutive failure(s)"));
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.INFO)
                .filteredOn(event -> event.getFormattedMessage().contains("again after"))
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .isEqualTo("Can receive messages from SQS queue orders again after 1 failure(s)"));
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.DEBUG)
                .filteredOn(event -> event.getFormattedMessage().startsWith("Cannot receive messages"))
                .hasSize(19);
    }

    @Test
    void shouldPauseAfterAnEmptyReceiveWhenShortPolling() {
        givenQueue(REDRIVE_POLICY);
        givenPolls(batch(), batch(), batch(message("m1", 1)));
        givenDeleteSucceeds();
        List<String> bodies = new CopyOnWriteArrayList<>();

        start(container(shortPolling(), body -> Mono.fromRunnable(() -> bodies.add(body))));
        scheduler.advanceTimeBy(SqsListenerContainer.SHORT_POLLING_IDLE_DELAY.minusMillis(1));
        verify(sqs, times(1)).receiveMessage(any(ReceiveMessageRequest.class));
        scheduler.advanceTimeBy(Duration.ofMillis(1));
        verify(sqs, times(2)).receiveMessage(any(ReceiveMessageRequest.class));
        scheduler.advanceTimeBy(SqsListenerContainer.SHORT_POLLING_IDLE_DELAY);

        assertThat(bodies).containsExactly("body-m1");
        // a non-empty receive is followed by the next poll right away
        ArgumentCaptor<ReceiveMessageRequest> receive = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(sqs, times(4)).receiveMessage(receive.capture());
        assertThat(receive.getValue().waitTimeSeconds()).isZero();
    }

    @Test
    void shouldPollAgainRightAfterAnEmptyLongPoll() {
        givenQueue(REDRIVE_POLICY);
        givenPolls(batch(), batch());

        start(container(body -> Mono.empty()));

        verify(sqs, times(3)).receiveMessage(any(ReceiveMessageRequest.class));
    }

    @Test
    void shouldCancelTheShortPollingPauseWhenStopping() {
        givenQueue(REDRIVE_POLICY);
        givenPolls(batch());
        AtomicBoolean stopped = new AtomicBoolean();
        SqsListenerContainer container = container(shortPolling(), body -> Mono.empty());
        start(container);

        container.stop(() -> stopped.set(true));
        scheduler.advanceTimeBy(Duration.ofMinutes(1));

        assertThat(stopped).isTrue();
        assertThat(container.isRunning()).isFalse();
        verify(sqs, times(1)).receiveMessage(any(ReceiveMessageRequest.class));
    }

    @Test
    void shouldRetryQueueResolutionUntilTheQueueExists() {
        when(sqs.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(QueueDoesNotExistException.builder().message("no queue").build()))
                .thenReturn(completedFuture(GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build()));
        when(sqs.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(completedFuture(redrive(REDRIVE_POLICY)));
        givenPolls();

        start(container(body -> Mono.empty()));
        verify(sqs, never()).receiveMessage(any(ReceiveMessageRequest.class));
        scheduler.advanceTimeBy(Duration.ofSeconds(2));

        verify(sqs).receiveMessage(any(ReceiveMessageRequest.class));
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("Cannot resolve the URL of SQS queue orders"));
    }

    @Test
    void shouldDisableDeadLetterDetectionWhenRedrivePolicyCannotBeRead() {
        when(sqs.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(completedFuture(GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build()));
        when(sqs.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("access denied")));
        givenPolls(batch(message("m1", 9)));
        givenVisibilityChangeSucceeds();

        start(container(body -> Mono.error(new IllegalStateException("boom"))));

        assertThat(messages(MessageOutcome.FAILED)).isEqualTo(1.0);
        assertThat(messages(MessageOutcome.POISON)).isZero();
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("Cannot read the redrive policy of SQS queue orders"));
    }

    @Test
    void shouldParseMaxReceiveCountOfRedrivePolicy() {
        assertThat(SqsListenerContainer.parseMaxReceiveCount(REDRIVE_POLICY)).hasValue(3);
        assertThat(SqsListenerContainer.parseMaxReceiveCount("{\"maxReceiveCount\": 5, \"deadLetterTargetArn\":\"a\"}"))
                .hasValue(5);
        assertThat(SqsListenerContainer.parseMaxReceiveCount("{\"deadLetterTargetArn\":\"a\"}")).isEmpty();
        assertThat(SqsListenerContainer.parseMaxReceiveCount(null)).isEmpty();
    }

    @Test
    void shouldBoundInFlightMessagesByConcurrencyAndPollOnlyWhenTheBatchIsHandedOver() {
        givenQueue(REDRIVE_POLICY);
        givenPolls(batch(messages("a", 10)), batch(messages("b", 10)));
        givenDeleteSucceeds();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        Queue<Sinks.Empty<Void>> running = new ConcurrentLinkedQueue<>();
        SqsListenerSettings settings = settings(3, 2, Duration.ofSeconds(30), SHUTDOWN_TIMEOUT);

        start(container(settings, body -> Mono.defer(() -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            Sinks.Empty<Void> done = Sinks.empty();
            running.add(done);
            return done.asMono().doOnSuccess(ignored -> inFlight.decrementAndGet());
        })));

        assertThat(inFlight).hasValue(3);
        assertThat(registry.get(SqsListenerMetrics.IN_FLIGHT).gauge().value()).isEqualTo(3.0);
        verify(sqs, times(2)).receiveMessage(any(ReceiveMessageRequest.class));
        int completed = 0;
        Sinks.Empty<Void> next;
        while ((next = running.poll()) != null) {
            next.tryEmitEmpty();
            completed++;
        }

        assertThat(completed).isEqualTo(20);
        assertThat(maxInFlight).hasValue(3);
        verify(sqs, times(20)).deleteMessage(any(DeleteMessageRequest.class));
        verify(sqs, times(4)).receiveMessage(any(ReceiveMessageRequest.class));
        assertThat(registry.get(SqsListenerMetrics.IN_FLIGHT).gauge().value()).isZero();
    }

    @Test
    void shouldStopPollingAndDrainInFlightMessagesBeforeRunningTheCallback() {
        givenQueue(REDRIVE_POLICY);
        givenPolls(batch(message("m1", 1)));
        givenDeleteSucceeds();
        Sinks.Empty<Void> handler = Sinks.empty();
        AtomicBoolean stopped = new AtomicBoolean();
        SqsListenerContainer container = container(body -> handler.asMono());
        start(container);
        assertThat(pendingPolls).hasSize(1);

        container.stop(() -> stopped.set(true));

        assertThat(pendingPolls.getFirst()).isCancelled();
        assertThat(stopped).isFalse();
        assertThat(container.isRunning()).isTrue();
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));

        handler.tryEmitEmpty();

        assertThat(stopped).isTrue();
        assertThat(container.isRunning()).isFalse();
        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
        verify(sqs, times(2)).receiveMessage(any(ReceiveMessageRequest.class));
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("draining 1 in-flight message(s) within PT30S"));
    }

    @Test
    void shouldCancelInFlightMessagesWhenDrainTimesOut() {
        givenQueue(REDRIVE_POLICY);
        givenPolls(batch(message("m1", 1)));
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean stopped = new AtomicBoolean();
        SqsListenerContainer container = container(body -> Mono.<Void>never().doOnCancel(() -> cancelled.set(true)));
        start(container);

        container.stop(() -> stopped.set(true));
        scheduler.advanceTimeBy(SHUTDOWN_TIMEOUT.minusMillis(1));
        assertThat(stopped).isFalse();
        scheduler.advanceTimeBy(Duration.ofMillis(1));

        assertThat(stopped).isTrue();
        assertThat(cancelled).isTrue();
        assertThat(container.isRunning()).isFalse();
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
        verify(sqs, never()).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("did not drain within PT30S: cancelling 1 in-flight message(s)"));
    }

    @Test
    void shouldRunEveryStopCallbackOnceDrained() {
        givenQueue(REDRIVE_POLICY);
        givenPolls(batch(message("m1", 1)));
        givenDeleteSucceeds();
        Sinks.Empty<Void> handler = Sinks.empty();
        AtomicInteger callbacks = new AtomicInteger();
        SqsListenerContainer container = container(body -> handler.asMono());
        start(container);

        container.stop(callbacks::incrementAndGet);
        container.stop(callbacks::incrementAndGet);
        assertThat(callbacks).hasValue(0);
        handler.tryEmitEmpty();

        assertThat(callbacks).hasValue(2);
        container.stop(callbacks::incrementAndGet);
        assertThat(callbacks).hasValue(3);
    }

    @Test
    void shouldStopWhileTheQueueIsStillUnresolved() {
        when(sqs.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("no network")));
        AtomicBoolean stopped = new AtomicBoolean();
        SqsListenerContainer container = container(body -> Mono.empty());
        start(container);

        container.stop(() -> stopped.set(true));
        scheduler.advanceTimeBy(Duration.ofMinutes(1));

        assertThat(stopped).isTrue();
        verify(sqs).getQueueUrl(any(GetQueueUrlRequest.class));
        verify(sqs, never()).receiveMessage(any(ReceiveMessageRequest.class));
    }

    @Test
    void shouldRunCallbackImmediatelyWhenNotRunning() {
        AtomicBoolean stopped = new AtomicBoolean();
        SqsListenerContainer container = container(body -> Mono.empty());

        container.stop(() -> stopped.set(true));

        assertThat(stopped).isTrue();
        assertThat(container.isRunning()).isFalse();
    }

    @Test
    void shouldIgnoreStartWhenAlreadyRunningAndRestartAfterStop() {
        givenQueue(REDRIVE_POLICY);
        givenPolls();
        SqsListenerContainer container = container(body -> Mono.empty());

        start(container);
        container.start();
        verify(sqs).getQueueUrl(any(GetQueueUrlRequest.class));

        container.stop();
        assertThat(container.isRunning()).isFalse();
        container.start();

        assertThat(container.isRunning()).isTrue();
        verify(sqs, times(2)).getQueueUrl(any(GetQueueUrlRequest.class));
        assertThat(pendingPolls).hasSize(2);
        assertThat(pendingPolls.getFirst()).isCancelled();
    }

    @Test
    void shouldWarnWhenSynchronousStopTimesOut() {
        givenQueue(REDRIVE_POLICY);
        givenPolls(batch(message("m1", 1)));
        SqsListenerSettings settings = settings(4, 1, Duration.ofSeconds(30), Duration.ofMillis(10));
        SqsListenerContainer container = container(settings, body -> Mono.never());
        start(container);

        container.stop();

        assertThat(container.isRunning()).isTrue();
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("SQS listeners are still stopping after PT1.01S"));
        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        assertThat(container.isRunning()).isFalse();
    }

    @Test
    void shouldRestoreInterruptFlagWhenInterruptedWhileStopping() {
        givenQueue(REDRIVE_POLICY);
        givenPolls(batch(message("m1", 1)));
        SqsListenerContainer container = container(body -> Mono.never());
        start(container);

        Thread.currentThread().interrupt();
        container.stop();

        assertThat(Thread.interrupted()).isTrue();
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("Interrupted while waiting for the SQS listeners to stop"));
    }

    @Test
    void shouldConsumeEveryRegisteredQueue() {
        when(sqs.getQueueUrl(any(GetQueueUrlRequest.class))).thenAnswer(invocation -> completedFuture(
                GetQueueUrlResponse.builder().queueUrl("http://sqs.local/" + invocation
                        .getArgument(0, GetQueueUrlRequest.class).queueName()).build()));
        when(sqs.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(completedFuture(redrive(REDRIVE_POLICY)));
        List<String> polledQueues = new CopyOnWriteArrayList<>();
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(invocation -> {
            String queueUrl = invocation.getArgument(0, ReceiveMessageRequest.class).queueUrl();
            boolean firstPoll = !polledQueues.contains(queueUrl);
            polledQueues.add(queueUrl);
            return firstPoll ? batch(message(queueUrl.substring(queueUrl.lastIndexOf('/') + 1), 1))
                    : new CompletableFuture<ReceiveMessageResponse>();
        });
        givenDeleteSucceeds();
        List<String> handled = new CopyOnWriteArrayList<>();
        SqsListenerContainer container = new SqsListenerContainer(sqs, List.of(
                new SqsQueueListener("reservations", body -> Mono.fromRunnable(() -> handled.add("reservations"))),
                new SqsQueueListener("payments", body -> Mono.fromRunnable(() -> handled.add("payments")))),
                SETTINGS, metrics, scheduler);

        start(container);

        assertThat(handled).containsExactlyInAnyOrder("reservations", "payments");
        assertThat(polledQueues).containsOnly("http://sqs.local/reservations", "http://sqs.local/payments");
        assertThat(registry.find(SqsListenerMetrics.MESSAGES).tag("queue", "payments").counter()).isNotNull();
    }

    @Test
    void shouldContainAnUnexpectedFailureOfOneMessage() {
        SqsListenerMetrics failingMetrics = mock(SqsListenerMetrics.class);
        when(failingMetrics.inFlight(QUEUE)).thenReturn(new AtomicInteger());
        doThrow(new IllegalStateException("registry down")).when(failingMetrics).record(any(), any(), any());
        givenQueue(REDRIVE_POLICY);
        givenPolls(batch(message("m1", 1), message("m2", 1)));
        givenDeleteSucceeds();
        SqsListenerContainer container = new SqsListenerContainer(sqs,
                List.of(new SqsQueueListener(QUEUE, body -> Mono.empty())), SETTINGS, failingMetrics, scheduler);

        start(container);

        verify(sqs, times(2)).deleteMessage(any(DeleteMessageRequest.class));
        assertThat(container.isRunning()).isTrue();
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.ERROR).hasSize(2)
                .allSatisfy(event -> assertThat(event.getFormattedMessage())
                        .startsWith("Unexpected failure while processing message"));
    }

    @Test
    void shouldLogAndTerminateWhenThePipelineFailsUnexpectedly() {
        SqsListenerMetrics failingMetrics = mock(SqsListenerMetrics.class);
        when(failingMetrics.inFlight(QUEUE)).thenThrow(new IllegalStateException("registry down"));
        givenQueue(REDRIVE_POLICY);
        SqsListenerContainer container = new SqsListenerContainer(sqs,
                List.of(new SqsQueueListener(QUEUE, body -> Mono.empty())), SETTINGS, failingMetrics, scheduler);

        container.start();

        assertThat(container.isRunning()).isFalse();
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.ERROR)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("SQS listener container failed",
                        "SQS listeners terminated unexpectedly (onComplete): no message is consumed until restart");
    }

    @Test
    void shouldStartAfterTheWebServerAndDrainTogetherWithItsGracefulShutdown() {
        SqsListenerContainer container = container(body -> Mono.empty());

        assertThat(container.getPhase()).isEqualTo(SqsListenerContainer.PHASE)
                .isEqualTo(WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE)
                .isGreaterThan(WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE)
                .isLessThan(SmartLifecycle.DEFAULT_PHASE);
        assertThat(container.isAutoStartup()).isTrue();
        assertThat(container.isRunning()).isFalse();
    }

    @Test
    void shouldValidateConstructorArguments() {
        List<SqsQueueListener> listeners = List.of(new SqsQueueListener(QUEUE, body -> Mono.empty()));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SqsListenerContainer(sqs, List.of(), SETTINGS, metrics, scheduler));
        assertThatNullPointerException()
                .isThrownBy(() -> new SqsListenerContainer(null, listeners, SETTINGS, metrics, scheduler));
        assertThatNullPointerException()
                .isThrownBy(() -> new SqsListenerContainer(sqs, listeners, null, metrics, scheduler));
        assertThatNullPointerException()
                .isThrownBy(() -> new SqsListenerContainer(sqs, listeners, SETTINGS, null, scheduler));
        assertThatNullPointerException()
                .isThrownBy(() -> new SqsListenerContainer(sqs, listeners, SETTINGS, metrics, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new SqsListenerContainer(sqs, listeners, SETTINGS, metrics, scheduler).stop(null));
    }

    private static SqsListenerSettings settings(int concurrency, int pollers, Duration pollRetryMaxBackoff,
                                                Duration shutdownTimeout) {
        return new SqsListenerSettings(10, 20, 60, concurrency, pollers, Duration.ofSeconds(5), Duration.ofMinutes(5),
                Duration.ofSeconds(1), pollRetryMaxBackoff, shutdownTimeout);
    }

    private static SqsListenerSettings shortPolling() {
        return new SqsListenerSettings(10, 0, 60, 4, 1, Duration.ofSeconds(5), Duration.ofMinutes(5),
                Duration.ofSeconds(1), Duration.ofSeconds(30), SHUTDOWN_TIMEOUT);
    }

    private SqsListenerContainer container(Function<String, Mono<Void>> handler) {
        return container(SETTINGS, handler);
    }

    private SqsListenerContainer container(SqsListenerSettings settings, Function<String, Mono<Void>> handler) {
        return new SqsListenerContainer(sqs, List.of(new SqsQueueListener(QUEUE, handler)), settings, metrics,
                scheduler);
    }

    private void start(SqsListenerContainer container) {
        containers.add(container);
        container.start();
        assertThat(container.isRunning()).isTrue();
    }

    private void givenQueue(String redrivePolicy) {
        when(sqs.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(completedFuture(GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build()));
        when(sqs.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(completedFuture(redrive(redrivePolicy)));
    }

    private static GetQueueAttributesResponse redrive(String redrivePolicy) {
        return GetQueueAttributesResponse.builder()
                .attributes(redrivePolicy == null ? Map.of() : Map.of(QueueAttributeName.REDRIVE_POLICY, redrivePolicy))
                .build();
    }

    /** Scripts the receive calls; once the script is exhausted every call returns a pending (long) poll. */
    @SafeVarargs
    private void givenPolls(CompletableFuture<ReceiveMessageResponse>... polls) {
        for (CompletableFuture<ReceiveMessageResponse> poll : polls) {
            scriptedPolls.add(poll);
        }
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(invocation -> {
            CompletableFuture<ReceiveMessageResponse> next = scriptedPolls.poll();
            if (next != null) {
                return next;
            }
            CompletableFuture<ReceiveMessageResponse> pending = new CompletableFuture<>();
            pendingPolls.add(pending);
            return pending;
        });
    }

    private void givenDeleteSucceeds() {
        when(sqs.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(completedFuture(DeleteMessageResponse.builder().build()));
    }

    private void givenVisibilityChangeSucceeds() {
        when(sqs.changeMessageVisibility(any(ChangeMessageVisibilityRequest.class)))
                .thenReturn(completedFuture(ChangeMessageVisibilityResponse.builder().build()));
    }

    private double messages(MessageOutcome outcome) {
        Counter counter = registry.find(SqsListenerMetrics.MESSAGES)
                .tags("queue", QUEUE, "outcome", outcome.tagValue())
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static CompletableFuture<ReceiveMessageResponse> batch(Message... messages) {
        return completedFuture(ReceiveMessageResponse.builder().messages(messages).build());
    }

    private static CompletableFuture<ReceiveMessageResponse> failure(Throwable error) {
        return CompletableFuture.failedFuture(error);
    }

    private static Message[] messages(String prefix, int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> message(prefix + i, 1)).toArray(Message[]::new);
    }

    private static Message message(String id, int receiveCount) {
        return Message.builder()
                .messageId(id)
                .receiptHandle("receipt-" + id)
                .body("body-" + id)
                .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, String.valueOf(receiveCount)))
                .messageAttributes(Map.of(SqsListenerContainer.CORRELATION_ID_ATTRIBUTE, stringAttribute("corr-" + id)))
                .build();
    }

    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
