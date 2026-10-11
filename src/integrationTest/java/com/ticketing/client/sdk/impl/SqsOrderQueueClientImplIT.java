package com.ticketing.client.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.client.mapper.SqsOrderQueueClientMapper;
import com.ticketing.config.aws.AwsClientConfig;
import com.ticketing.config.properties.AwsProperties;
import com.ticketing.domain.exception.MessagePublishingException;
import com.ticketing.domain.internal_request.OrderReservationMessage;
import com.ticketing.domain.internal_request.PaymentRequestedMessage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class SqsOrderQueueClientImplIT {
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(30);
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-10T12:00:00.250Z");

    @Container
    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.9")).withServices("sqs");

    private static SqsAsyncClient sqs;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @BeforeAll
    static void createClient() {
        sqs = sqsClient(LOCALSTACK.getEndpoint().toString());
    }

    private static SqsAsyncClient sqsClient(String endpoint) {
        AwsProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "aws.region", LOCALSTACK.getRegion(),
                "aws.endpoint-override.sqs-endpoint", endpoint)))
                .bindOrCreate("aws", AwsProperties.class);
        return new AwsClientConfig().sqsAsyncClient(properties, StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())));
    }

    @AfterAll
    static void closeClient() {
        sqs.close();
    }

    private static String uniqueQueueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String createQueue(String name) {
        return sqs.createQueue(request -> request.queueName(name)).join().queueUrl();
    }

    private SqsOrderQueueClientImpl newClient(String reservationQueue, String paymentQueue) {
        return new SqsOrderQueueClientImpl(sqs, new SqsQueueNames(reservationQueue, paymentQueue),
                SqsOrderQueueClientMapper.INSTANCE, jsonMapper);
    }

    private static Message receiveOne(String queueUrl) {
        List<Message> messages = sqs.receiveMessage(request -> request
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(1)
                        .waitTimeSeconds(5)
                        .messageAttributeNames("All"))
                .join()
                .messages();
        assertThat(messages).hasSize(1);
        return messages.getFirst();
    }

    private static String approximateNumberOfMessages(String queueUrl) {
        return sqs.getQueueAttributes(request -> request
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                .join()
                .attributes()
                .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
    }

    private static void assertAttribute(Message message, String name, String dataType, String value) {
        MessageAttributeValue attribute = message.messageAttributes().get(name);
        assertThat(attribute).as("attribute %s", name).isNotNull();
        assertThat(attribute.dataType()).isEqualTo(dataType);
        assertThat(attribute.stringValue()).isEqualTo(value);
    }

    @Test
    void shouldPublishAReservationMessageThatConsumersCanRead() {
        String reservationQueue = uniqueQueueName("reservation");
        String reservationUrl = createQueue(reservationQueue);
        SqsOrderQueueClientImpl client = newClient(reservationQueue, uniqueQueueName("payment"));
        AtomicReference<String> messageId = new AtomicReference<>();

        StepVerifier.create(client.publishReservationRequested(
                        new OrderReservationMessage("order-1", "event-1", "corr-1", OCCURRED_AT)))
                .assertNext(messageId::set)
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        Message message = receiveOne(reservationUrl);
        assertThat(message.messageId()).isEqualTo(messageId.get());
        JsonNode body = jsonMapper.readTree(message.body());
        assertThat(body.get("orderId").asString()).isEqualTo("order-1");
        assertThat(body.get("eventId").asString()).isEqualTo("event-1");
        assertThat(body.get("correlationId").asString()).isEqualTo("corr-1");
        assertThat(body.get("occurredAt").asString()).isEqualTo("2026-10-10T12:00:00.250Z");
        assertAttribute(message, "messageType", "String", "ORDER_RESERVATION_REQUESTED");
        assertAttribute(message, "correlationId", "String", "corr-1");
        assertAttribute(message, "schemaVersion", "Number", "1");
    }

    @Test
    void shouldPublishAPaymentMessageToThePaymentQueue() {
        String reservationQueue = uniqueQueueName("reservation");
        String paymentQueue = uniqueQueueName("payment");
        String reservationUrl = createQueue(reservationQueue);
        String paymentUrl = createQueue(paymentQueue);
        SqsOrderQueueClientImpl client = newClient(reservationQueue, paymentQueue);

        StepVerifier.create(client.publishPaymentRequested(
                        new PaymentRequestedMessage("order-2", "tok_abc123", "corr-2", OCCURRED_AT)))
                .expectNextCount(1)
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        Message message = receiveOne(paymentUrl);
        JsonNode body = jsonMapper.readTree(message.body());
        assertThat(body.get("orderId").asString()).isEqualTo("order-2");
        assertThat(body.get("paymentToken").asString()).isEqualTo("tok_abc123");
        assertThat(body.get("correlationId").asString()).isEqualTo("corr-2");
        assertAttribute(message, "messageType", "String", "PAYMENT_REQUESTED");
        assertAttribute(message, "schemaVersion", "Number", "1");
        assertThat(approximateNumberOfMessages(reservationUrl)).isEqualTo("0");
    }

    @Test
    void shouldResolveTheQueueAgainAfterAFailedLookup() {
        String reservationQueue = uniqueQueueName("late-reservation");
        SqsOrderQueueClientImpl client = newClient(reservationQueue, uniqueQueueName("payment"));
        OrderReservationMessage message = new OrderReservationMessage("order-3", "event-3", "corr-3", OCCURRED_AT);

        StepVerifier.create(client.publishReservationRequested(message))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(MessagePublishingException.class)
                        .hasCauseInstanceOf(QueueDoesNotExistException.class))
                .verify(VERIFY_TIMEOUT);

        String reservationUrl = createQueue(reservationQueue);

        StepVerifier.create(client.publishReservationRequested(message))
                .expectNextCount(1)
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
        assertThat(jsonMapper.readTree(receiveOne(reservationUrl).body()).get("orderId").asString())
                .isEqualTo("order-3");
    }

    @Test
    void shouldPublishAGeneratedCorrelationIdInBodyAndAttributesWhenTheMessageHasNone() {
        String reservationQueue = uniqueQueueName("sweeper-reservation");
        String reservationUrl = createQueue(reservationQueue);
        SqsOrderQueueClientImpl client = newClient(reservationQueue, uniqueQueueName("payment"));

        StepVerifier.create(client.publishReservationRequested(
                        new OrderReservationMessage("order-4", "event-4", null, OCCURRED_AT)))
                .expectNextCount(1)
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        Message message = receiveOne(reservationUrl);
        JsonNode correlationId = jsonMapper.readTree(message.body()).get("correlationId");
        assertThat(correlationId.isString()).isTrue();
        assertThat(correlationId.asString()).matches(SqsOrderQueueClientMapper.CORRELATION_ID_PATTERN);
        assertAttribute(message, "correlationId", "String", correlationId.asString());
    }

    @Test
    void shouldGiveUpWithinThePublishTimeoutWhenSqsNeverAnswersTheQueueUrlLookup() throws IOException {
        try (ServerSocket silentEndpoint = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
             SqsAsyncClient silentSqs = sqsClient("http://127.0.0.1:" + silentEndpoint.getLocalPort())) {
            SqsOrderQueueClientImpl client = new SqsOrderQueueClientImpl(silentSqs,
                    new SqsQueueNames("unreachable-reservation", "unreachable-payment"),
                    SqsOrderQueueClientMapper.INSTANCE, jsonMapper);
            long startNanos = System.nanoTime();

            StepVerifier.create(client.publishReservationRequested(
                            new OrderReservationMessage("order-5", "event-5", "corr-5", OCCURRED_AT)))
                    .expectError(MessagePublishingException.class)
                    .verify(SqsOrderQueueClientImpl.PUBLISH_CALL_TIMEOUT.plusSeconds(5));

            assertThat(Duration.ofNanos(System.nanoTime() - startNanos))
                    .isLessThan(SqsOrderQueueClientImpl.PUBLISH_CALL_TIMEOUT.plusSeconds(3));
        }
    }

    @Test
    void shouldPublishConcurrentlyWithoutLosingMessages() {
        String reservationQueue = uniqueQueueName("burst-reservation");
        String reservationUrl = createQueue(reservationQueue);
        SqsOrderQueueClientImpl client = newClient(reservationQueue, uniqueQueueName("payment"));
        int publications = 50;

        StepVerifier.create(Flux.range(1, publications)
                        .flatMap(i -> client.publishReservationRequested(
                                new OrderReservationMessage("order-" + i, "event-1", "corr-" + i, OCCURRED_AT)), 16)
                        .collectList())
                .assertNext(ids -> assertThat(ids).hasSize(publications).doesNotHaveDuplicates())
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        assertThat(approximateNumberOfMessages(reservationUrl)).isEqualTo(String.valueOf(publications));
    }
}
