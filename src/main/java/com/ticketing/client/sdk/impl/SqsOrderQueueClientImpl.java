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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
public final class SqsOrderQueueClientImpl implements SqsOrderQueueClient {
    static final Duration PUBLISH_CALL_TIMEOUT = Duration.ofSeconds(5);

    static final Duration PUBLISH_ATTEMPT_TIMEOUT = Duration.ofSeconds(2);

    static final AwsRequestOverrideConfiguration PUBLISH_OVERRIDE = AwsRequestOverrideConfiguration.builder()
            .apiCallTimeout(PUBLISH_CALL_TIMEOUT)
            .apiCallAttemptTimeout(PUBLISH_ATTEMPT_TIMEOUT)
            .build();

    private static final Duration CACHE_FOREVER = Duration.ofMillis(Long.MAX_VALUE);

    private final SqsAsyncClient sqsAsyncClient;
    private final SqsOrderQueueClientMapper mapper;
    private final JsonMapper jsonMapper;
    private final Mono<String> orderReservationQueueUrl;
    private final Mono<String> orderPaymentQueueUrl;

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

    private SendMessageRequest sendMessageRequest(String queueUrl, MessageTypeEnum messageType, String correlationId,
                                                  Object body) {
        return SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(jsonMapper.writeValueAsString(body))
                .messageAttributes(mapper.toMessageAttributes(messageType, correlationId))
                .overrideConfiguration(PUBLISH_OVERRIDE)
                .build();
    }

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
