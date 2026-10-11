package com.ticketing.client.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.client.mapper.SqsOrderQueueClientMapper;
import com.ticketing.domain.exception.MessagePublishingException;
import com.ticketing.domain.internal_request.OrderReservationMessage;
import com.ticketing.domain.internal_request.PaymentRequestedMessage;
import com.ticketing.enums.ErrorCodeEnum;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class SqsOrderQueueClientImplTest {

    private static final String RESERVATION_QUEUE = "ticketing-order-reservation";
    private static final String PAYMENT_QUEUE = "ticketing-order-payment";
    private static final String RESERVATION_URL = "http://sqs.local/000000000000/" + RESERVATION_QUEUE;
    private static final String PAYMENT_URL = "http://sqs.local/000000000000/" + PAYMENT_QUEUE;
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-10T12:00:00Z");
    private static final OrderReservationMessage RESERVATION =
            new OrderReservationMessage("order-1", "event-1", "corr-1", OCCURRED_AT);
    private static final PaymentRequestedMessage PAYMENT =
            new PaymentRequestedMessage("order-1", "tok_secret", "corr-1", OCCURRED_AT);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private SqsAsyncClient sqsAsyncClient;

    private SqsOrderQueueClientImpl newClient() {
        return new SqsOrderQueueClientImpl(sqsAsyncClient, new SqsQueueNames(RESERVATION_QUEUE, PAYMENT_QUEUE),
                new SqsOrderQueueClientMapper(), jsonMapper);
    }

    private static CompletableFuture<GetQueueUrlResponse> queueUrl(String url) {
        return CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl(url).build());
    }

    private static CompletableFuture<SendMessageResponse> sent(String messageId) {
        return CompletableFuture.completedFuture(SendMessageResponse.builder().messageId(messageId).build());
    }

    private static GetQueueUrlRequest queueUrlRequest(String queueName) {
        return GetQueueUrlRequest.builder()
                .queueName(queueName)
                .overrideConfiguration(SqsOrderQueueClientImpl.PUBLISH_OVERRIDE)
                .build();
    }

    private SendMessageRequest capturedSendRequest() {
        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsAsyncClient).sendMessage(captor.capture());
        return captor.getValue();
    }

    @Test
    void shouldPublishReservationMessageWithJsonBodyAndAttributes() {
        when(sqsAsyncClient.getQueueUrl(queueUrlRequest(RESERVATION_QUEUE))).thenReturn(queueUrl(RESERVATION_URL));
        when(sqsAsyncClient.sendMessage(any(SendMessageRequest.class))).thenReturn(sent("msg-1"));

        StepVerifier.create(newClient().publishReservationRequested(RESERVATION))
                .expectNext("msg-1")
                .verifyComplete();

        SendMessageRequest request = capturedSendRequest();
        assertThat(request.queueUrl()).isEqualTo(RESERVATION_URL);
        JsonNode body = jsonMapper.readTree(request.messageBody());
        assertThat(body.get("orderId").asString()).isEqualTo("order-1");
        assertThat(body.get("eventId").asString()).isEqualTo("event-1");
        assertThat(body.get("correlationId").asString()).isEqualTo("corr-1");
        assertThat(body.get("occurredAt").asString()).isEqualTo("2026-10-10T12:00:00Z");
        assertThat(request.messageAttributes().get("messageType").stringValue())
                .isEqualTo("ORDER_RESERVATION_REQUESTED");
        assertThat(request.messageAttributes().get("correlationId").stringValue()).isEqualTo("corr-1");
        assertThat(request.messageAttributes().get("schemaVersion").stringValue()).isEqualTo("1");
        assertThat(request.overrideConfiguration()).hasValueSatisfying(override -> {
            assertThat(override.apiCallTimeout()).contains(SqsOrderQueueClientImpl.PUBLISH_CALL_TIMEOUT);
            assertThat(override.apiCallAttemptTimeout()).contains(SqsOrderQueueClientImpl.PUBLISH_ATTEMPT_TIMEOUT);
        });
    }

    @Test
    void shouldPublishPaymentMessageToThePaymentQueue() {
        when(sqsAsyncClient.getQueueUrl(queueUrlRequest(PAYMENT_QUEUE))).thenReturn(queueUrl(PAYMENT_URL));
        when(sqsAsyncClient.sendMessage(any(SendMessageRequest.class))).thenReturn(sent("msg-2"));

        StepVerifier.create(newClient().publishPaymentRequested(PAYMENT))
                .expectNext("msg-2")
                .verifyComplete();

        SendMessageRequest request = capturedSendRequest();
        assertThat(request.queueUrl()).isEqualTo(PAYMENT_URL);
        JsonNode body = jsonMapper.readTree(request.messageBody());
        assertThat(body.get("orderId").asString()).isEqualTo("order-1");
        assertThat(body.get("paymentToken").asString()).isEqualTo("tok_secret");
        assertThat(body.get("correlationId").asString()).isEqualTo("corr-1");
        assertThat(request.messageAttributes().get("messageType").stringValue()).isEqualTo("PAYMENT_REQUESTED");
    }

    @Test
    void shouldResolveEachQueueUrlOnlyOnce() {
        when(sqsAsyncClient.getQueueUrl(queueUrlRequest(RESERVATION_QUEUE))).thenReturn(queueUrl(RESERVATION_URL));
        when(sqsAsyncClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(sent("msg-1"))
                .thenReturn(sent("msg-2"));
        SqsOrderQueueClientImpl client = newClient();

        StepVerifier.create(client.publishReservationRequested(RESERVATION)).expectNext("msg-1").verifyComplete();
        StepVerifier.create(client.publishReservationRequested(RESERVATION)).expectNext("msg-2").verifyComplete();

        verify(sqsAsyncClient, times(1)).getQueueUrl(any(GetQueueUrlRequest.class));
        verify(sqsAsyncClient, times(2)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void shouldShareOneQueueUrlLookupBetweenConcurrentPublications() {
        CompletableFuture<GetQueueUrlResponse> pendingLookup = new CompletableFuture<>();
        when(sqsAsyncClient.getQueueUrl(queueUrlRequest(RESERVATION_QUEUE))).thenReturn(pendingLookup);
        when(sqsAsyncClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(sent("msg-1"))
                .thenReturn(sent("msg-2"));
        SqsOrderQueueClientImpl client = newClient();

        StepVerifier.create(Mono.zip(client.publishReservationRequested(RESERVATION),
                        client.publishReservationRequested(RESERVATION)))
                .then(() -> pendingLookup.complete(GetQueueUrlResponse.builder().queueUrl(RESERVATION_URL).build()))
                .assertNext(ids -> assertThat(ids.toList()).containsExactlyInAnyOrder("msg-1", "msg-2"))
                .verifyComplete();

        verify(sqsAsyncClient, times(1)).getQueueUrl(any(GetQueueUrlRequest.class));
    }

    @Test
    void shouldNotCacheAFailedQueueUrlLookup() {
        when(sqsAsyncClient.getQueueUrl(queueUrlRequest(RESERVATION_QUEUE)))
                .thenReturn(CompletableFuture.failedFuture(QueueDoesNotExistException.builder()
                        .message("queue does not exist").build()))
                .thenReturn(queueUrl(RESERVATION_URL));
        when(sqsAsyncClient.sendMessage(any(SendMessageRequest.class))).thenReturn(sent("msg-1"));
        SqsOrderQueueClientImpl client = newClient();

        StepVerifier.create(client.publishReservationRequested(RESERVATION))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(MessagePublishingException.class)
                        .hasCauseInstanceOf(QueueDoesNotExistException.class))
                .verify();
        StepVerifier.create(client.publishReservationRequested(RESERVATION))
                .expectNext("msg-1")
                .verifyComplete();

        verify(sqsAsyncClient, times(2)).getQueueUrl(any(GetQueueUrlRequest.class));
    }

    @Test
    void shouldMapSendFailureToRetryableMessagePublishingException() {
        when(sqsAsyncClient.getQueueUrl(queueUrlRequest(PAYMENT_QUEUE))).thenReturn(queueUrl(PAYMENT_URL));
        when(sqsAsyncClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("connection reset")));

        StepVerifier.create(newClient().publishPaymentRequested(PAYMENT))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(MessagePublishingException.class)
                            .hasCauseInstanceOf(SdkClientException.class)
                            .hasMessageContaining("PAYMENT_REQUESTED")
                            .hasMessageContaining("order-1")
                            .hasMessageNotContaining("tok_secret");
                    MessagePublishingException publishingError = (MessagePublishingException) error;
                    assertThat(publishingError.errorCode()).isEqualTo(ErrorCodeEnum.MESSAGE_PUBLISHING_FAILED);
                    assertThat(publishingError.retryable()).isTrue();
                })
                .verify();
    }

    @Test
    void shouldMapSynchronousSdkExceptionToMessagePublishingException() {
        when(sqsAsyncClient.getQueueUrl(queueUrlRequest(RESERVATION_QUEUE))).thenReturn(queueUrl(RESERVATION_URL));
        when(sqsAsyncClient.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(SdkClientException.create("invalid request"));

        StepVerifier.create(newClient().publishReservationRequested(RESERVATION))
                .expectError(MessagePublishingException.class)
                .verify();
    }

    @Test
    void shouldMapSerializationFailureToMessagePublishingExceptionWithoutSending() {
        JsonMapper failingJsonMapper = mock(JsonMapper.class);
        when(failingJsonMapper.writeValueAsString(any())).thenThrow(new IllegalStateException("not serializable"));
        when(sqsAsyncClient.getQueueUrl(queueUrlRequest(RESERVATION_QUEUE))).thenReturn(queueUrl(RESERVATION_URL));
        SqsOrderQueueClientImpl client = new SqsOrderQueueClientImpl(sqsAsyncClient,
                new SqsQueueNames(RESERVATION_QUEUE, PAYMENT_QUEUE), new SqsOrderQueueClientMapper(),
                failingJsonMapper);

        StepVerifier.create(client.publishReservationRequested(RESERVATION))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(MessagePublishingException.class)
                        .hasCauseInstanceOf(IllegalStateException.class))
                .verify();

        verify(sqsAsyncClient, never()).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void shouldSendAgainOnResubscription() {
        when(sqsAsyncClient.getQueueUrl(queueUrlRequest(RESERVATION_QUEUE))).thenReturn(queueUrl(RESERVATION_URL));
        when(sqsAsyncClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("throttled")))
                .thenReturn(sent("msg-1"));

        StepVerifier.create(newClient().publishReservationRequested(RESERVATION).retry(1))
                .expectNext("msg-1")
                .verifyComplete();

        verify(sqsAsyncClient, times(2)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void shouldNotCallAwsBeforeSubscription() {
        SqsOrderQueueClientImpl client = newClient();

        Mono<String> publication = client.publishReservationRequested(RESERVATION);

        assertThat(publication).isNotNull();
        verify(sqsAsyncClient, never()).getQueueUrl(any(GetQueueUrlRequest.class));
    }

    @Test
    void shouldBoundTheQueueUrlLookupWithThePublishTimeoutsInsteadOfTheLongPollingDefaults() {
        when(sqsAsyncClient.getQueueUrl(any(GetQueueUrlRequest.class))).thenReturn(queueUrl(PAYMENT_URL));
        when(sqsAsyncClient.sendMessage(any(SendMessageRequest.class))).thenReturn(sent("msg-1"));

        StepVerifier.create(newClient().publishPaymentRequested(PAYMENT)).expectNext("msg-1").verifyComplete();

        ArgumentCaptor<GetQueueUrlRequest> captor = ArgumentCaptor.forClass(GetQueueUrlRequest.class);
        verify(sqsAsyncClient).getQueueUrl(captor.capture());
        assertThat(captor.getValue().queueName()).isEqualTo(PAYMENT_QUEUE);
        assertThat(captor.getValue().overrideConfiguration()).hasValueSatisfying(override -> {
            assertThat(override.apiCallTimeout()).contains(SqsOrderQueueClientImpl.PUBLISH_CALL_TIMEOUT);
            assertThat(override.apiCallAttemptTimeout()).contains(SqsOrderQueueClientImpl.PUBLISH_ATTEMPT_TIMEOUT);
        });
    }

    @Test
    void shouldFailWithinThePublishTimeoutWhenTheQueueUrlLookupNeverCompletes() {
        CompletableFuture<GetQueueUrlResponse> hangingLookup = new CompletableFuture<>();
        when(sqsAsyncClient.getQueueUrl(queueUrlRequest(RESERVATION_QUEUE))).thenReturn(hangingLookup);
        Duration timeout = SqsOrderQueueClientImpl.PUBLISH_CALL_TIMEOUT;

        StepVerifier.withVirtualTime(() -> newClient().publishReservationRequested(RESERVATION))
                .expectSubscription()
                .expectNoEvent(timeout.minusMillis(1))
                .thenAwait(Duration.ofMillis(1))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(MessagePublishingException.class)
                        .hasCauseInstanceOf(TimeoutException.class))
                .verify(Duration.ofSeconds(5));

        assertThat(hangingLookup).isCancelled();
        verify(sqsAsyncClient, never()).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void shouldLookUpAgainAfterATimedOutLookup() {
        when(sqsAsyncClient.getQueueUrl(queueUrlRequest(RESERVATION_QUEUE)))
                .thenReturn(new CompletableFuture<>())
                .thenReturn(queueUrl(RESERVATION_URL));
        when(sqsAsyncClient.sendMessage(any(SendMessageRequest.class))).thenReturn(sent("msg-1"));

        StepVerifier.withVirtualTime(() -> {
                    SqsOrderQueueClientImpl client = newClient();
                    return client.publishReservationRequested(RESERVATION)
                            .onErrorResume(MessagePublishingException.class,
                                    _ -> client.publishReservationRequested(RESERVATION));
                })
                .expectSubscription()
                .thenAwait(SqsOrderQueueClientImpl.PUBLISH_CALL_TIMEOUT)
                .expectNext("msg-1")
                .verifyComplete();

        verify(sqsAsyncClient, times(2)).getQueueUrl(any(GetQueueUrlRequest.class));
    }

    @Test
    void shouldPublishTheSameGeneratedCorrelationIdInBodyAndAttributesOnEveryAttempt() {
        when(sqsAsyncClient.getQueueUrl(queueUrlRequest(RESERVATION_QUEUE))).thenReturn(queueUrl(RESERVATION_URL));
        when(sqsAsyncClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("throttled")))
                .thenReturn(sent("msg-1"));
        OrderReservationMessage withoutCorrelationId =
                new OrderReservationMessage("order-1", "event-1", null, OCCURRED_AT);

        StepVerifier.create(newClient().publishReservationRequested(withoutCorrelationId).retry(1))
                .expectNext("msg-1")
                .verifyComplete();

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsAsyncClient, times(2)).sendMessage(captor.capture());
        List<String> bodyIds = captor.getAllValues().stream()
                .map(request -> jsonMapper.readTree(request.messageBody()).get("correlationId").asString())
                .toList();
        List<String> attributeIds = captor.getAllValues().stream()
                .map(request -> request.messageAttributes().get("correlationId").stringValue())
                .toList();
        assertThat(bodyIds).hasSize(2).containsOnly(bodyIds.getFirst());
        assertThat(bodyIds.getFirst()).matches(SqsOrderQueueClientMapper.CORRELATION_ID_PATTERN);
        assertThat(attributeIds).isEqualTo(bodyIds);
    }

    @Test
    void shouldPublishAPaymentMessageWithAGeneratedCorrelationIdWhenTheMessageHasNone() {
        when(sqsAsyncClient.getQueueUrl(queueUrlRequest(PAYMENT_QUEUE))).thenReturn(queueUrl(PAYMENT_URL));
        when(sqsAsyncClient.sendMessage(any(SendMessageRequest.class))).thenReturn(sent("msg-2"));

        StepVerifier.create(newClient().publishPaymentRequested(
                        new PaymentRequestedMessage("order-1", "tok_secret", " ", OCCURRED_AT)))
                .expectNext("msg-2")
                .verifyComplete();

        SendMessageRequest request = capturedSendRequest();
        String bodyId = jsonMapper.readTree(request.messageBody()).get("correlationId").asString();
        assertThat(bodyId).matches(SqsOrderQueueClientMapper.CORRELATION_ID_PATTERN);
        assertThat(request.messageAttributes().get("correlationId").stringValue()).isEqualTo(bodyId);
    }

    @Test
    void shouldRejectNullArguments() {
        SqsOrderQueueClientImpl client = newClient();
        SqsQueueNames names = new SqsQueueNames(RESERVATION_QUEUE, PAYMENT_QUEUE);
        SqsOrderQueueClientMapper mapper = new SqsOrderQueueClientMapper();

        assertThatNullPointerException().isThrownBy(() -> client.publishReservationRequested(null));
        assertThatNullPointerException().isThrownBy(() -> client.publishPaymentRequested(null));
        assertThatNullPointerException()
                .isThrownBy(() -> new SqsOrderQueueClientImpl(null, names, mapper, jsonMapper));
        assertThatNullPointerException()
                .isThrownBy(() -> new SqsOrderQueueClientImpl(sqsAsyncClient, null, mapper, jsonMapper));
        assertThatNullPointerException()
                .isThrownBy(() -> new SqsOrderQueueClientImpl(sqsAsyncClient, names, null, jsonMapper));
        assertThatNullPointerException()
                .isThrownBy(() -> new SqsOrderQueueClientImpl(sqsAsyncClient, names, mapper, null));
    }
}
