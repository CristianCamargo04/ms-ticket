package com.ticketing.client.sdk.impl;

import com.ticketing.client.dto.sqs.OrderReservationMessageDto;
import com.ticketing.client.dto.sqs.PaymentRequestedMessageDto;
import com.ticketing.client.mapper.SqsOrderQueueClientMapper;
import com.ticketing.client.sdk.SqsOrderQueueClient;
import com.ticketing.domain.exception.MessagePublishingException;
import com.ticketing.domain.internal_request.OrderReservationMessage;
import com.ticketing.domain.internal_request.PaymentRequestedMessage;
import com.ticketing.enums.MessageTypeEnum;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes order messages to SQS with the asynchronous (Netty) SDK client.
 *
 * <ul>
 *   <li>Each queue URL is resolved lazily with {@code GetQueueUrl} on first use and then cached for the lifetime of
 *   the client; concurrent first publications share a single lookup. A failed lookup is <em>not</em> cached: the
 *   next publication resolves it again.</li>
 *   <li>The body is the JSON of a {@code client.dto.sqs} DTO; the attributes are {@code messageType},
 *   {@code correlationId} (always the body's, see {@link SqsOrderQueueClientMapper}) and {@code schemaVersion}. The
 *   body is mapped once per call, so a caller retry re-sends exactly the same body.</li>
 *   <li>Every SDK call ({@code GetQueueUrl} and {@code SendMessage}) is created per subscription
 *   ({@code Mono.fromFuture(Supplier)}), so a caller retry really re-executes it, and carries
 *   {@link #PUBLISH_CALL_TIMEOUT} / {@link #PUBLISH_ATTEMPT_TIMEOUT}: the shared SQS client is sized for 20 s long
 *   polling, which is far too long for the API path that publishes. The shared lookup is additionally bounded by a
 *   Reactor timeout, so that no client configuration can make every publication wait on it. A publication therefore
 *   takes at most {@code PUBLISH_CALL_TIMEOUT} once the URL is known, and twice that on the first one.</li>
 *   <li>Any failure is an error signal {@link MessagePublishingException} (retryable). Message bodies are never
 *   logged, so payment tokens never reach the logs.</li>
 * </ul>
 */
@Component
public final class SqsOrderQueueClientImpl implements SqsOrderQueueClient {

    /** Upper bound of a single SDK call (queue URL lookup or publication), including the SDK retries. */
    static final Duration PUBLISH_CALL_TIMEOUT = Duration.ofSeconds(5);

    /** Upper bound of a single attempt of an SDK call. */
    static final Duration PUBLISH_ATTEMPT_TIMEOUT = Duration.ofSeconds(2);

    /** Per-request timeouts of every call made by the publisher. */
    static final AwsRequestOverrideConfiguration PUBLISH_OVERRIDE = AwsRequestOverrideConfiguration.builder()
            .apiCallTimeout(PUBLISH_CALL_TIMEOUT)
            .apiCallAttemptTimeout(PUBLISH_ATTEMPT_TIMEOUT)
            .build();

    /** Time-to-live of a resolved queue URL: forever (Reactor treats this value as infinite). */
    private static final Duration CACHE_FOREVER = Duration.ofMillis(Long.MAX_VALUE);

    private static final Logger log = LoggerFactory.getLogger(SqsOrderQueueClientImpl.class);

    private final SqsAsyncClient sqsAsyncClient;
    private final SqsOrderQueueClientMapper mapper;
    private final JsonMapper jsonMapper;
    private final Mono<String> orderReservationQueueUrl;
    private final Mono<String> orderPaymentQueueUrl;

    /**
     * Creates the client; no AWS call happens until the first publication.
     *
     * @param sqsAsyncClient the SQS client
     * @param queueNames     names of the target queues
     * @param mapper         domain to wire mapper
     * @param jsonMapper     JSON serializer of the message bodies
     */
    public SqsOrderQueueClientImpl(SqsAsyncClient sqsAsyncClient, SqsQueueNames queueNames,
                                   SqsOrderQueueClientMapper mapper, JsonMapper jsonMapper) {
        this.sqsAsyncClient = Objects.requireNonNull(sqsAsyncClient, "sqsAsyncClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        Objects.requireNonNull(queueNames, "queueNames");
        this.orderReservationQueueUrl = cachedQueueUrl(sqsAsyncClient, queueNames.orderReservationQueue());
        this.orderPaymentQueueUrl = cachedQueueUrl(sqsAsyncClient, queueNames.orderPaymentQueue());
    }

    @Override
    public Mono<String> publishReservationRequested(OrderReservationMessage message) {
        Objects.requireNonNull(message, "message");
        OrderReservationMessageDto body = mapper.toDto(message);
        return publish(orderReservationQueueUrl, MessageTypeEnum.ORDER_RESERVATION_REQUESTED, message.orderId(),
                body.correlationId(), body);
    }

    @Override
    public Mono<String> publishPaymentRequested(PaymentRequestedMessage message) {
        Objects.requireNonNull(message, "message");
        PaymentRequestedMessageDto body = mapper.toDto(message);
        return publish(orderPaymentQueueUrl, MessageTypeEnum.PAYMENT_REQUESTED, message.orderId(),
                body.correlationId(), body);
    }

    private Mono<String> publish(Mono<String> queueUrl, MessageTypeEnum messageType, String orderId,
                                 String correlationId, Object body) {
        return queueUrl
                .map(url -> sendMessageRequest(url, messageType, correlationId, body))
                .flatMap(request -> Mono.fromFuture(() -> sqsAsyncClient.sendMessage(request)))
                .map(SendMessageResponse::messageId)
                .doOnNext(messageId -> log.debug("Published {} for order {} (messageId={}, correlationId={})",
                        messageType, orderId, messageId, correlationId))
                .onErrorMap(error -> {
                    log.warn("Failed to publish {} for order {}: {}", messageType, orderId, error.toString());
                    return new MessagePublishingException(
                            "Failed to publish " + messageType + " for order " + orderId, error);
                });
    }

    /**
     * Builds the request; a serialization failure is thrown here and becomes an error signal of the {@code map}.
     */
    private SendMessageRequest sendMessageRequest(String queueUrl, MessageTypeEnum messageType, String correlationId,
                                                  Object body) {
        return SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(jsonMapper.writeValueAsString(body))
                .messageAttributes(mapper.toMessageAttributes(messageType, correlationId))
                .overrideConfiguration(PUBLISH_OVERRIDE)
                .build();
    }

    /**
     * Resolves a queue URL once and replays it to every later subscriber. The shared lookup is bounded (SDK
     * timeouts plus a Reactor timeout that cancels the SDK future), and errors (and an impossible empty result)
     * expire immediately, so a slow or failed lookup is retried by the next publication instead of being cached.
     */
    private static Mono<String> cachedQueueUrl(SqsAsyncClient sqsAsyncClient, String queueName) {
        GetQueueUrlRequest request = GetQueueUrlRequest.builder()
                .queueName(queueName)
                .overrideConfiguration(PUBLISH_OVERRIDE)
                .build();
        return Mono.fromFuture(() -> sqsAsyncClient.getQueueUrl(request))
                .map(GetQueueUrlResponse::queueUrl)
                .timeout(PUBLISH_CALL_TIMEOUT)
                .cache(_ -> CACHE_FOREVER, _ -> Duration.ZERO, () -> Duration.ZERO);
    }
}
