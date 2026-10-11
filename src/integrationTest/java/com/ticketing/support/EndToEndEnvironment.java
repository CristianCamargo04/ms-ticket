package com.ticketing.support;

import com.ticketing.config.properties.AwsProperties;
import com.ticketing.persistence.support.DynamoDbLocalContainer;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

public final class EndToEndEnvironment {
    public static final DockerImageName LOCALSTACK_IMAGE = DockerImageName.parse("localstack/localstack:4.9");

    public static final String PAYMENT_API_KEY = "e2e-payment-api-key";

    private static final int MAX_RECEIVE_COUNT = 5;
    private static final String DUMMY_CREDENTIAL = "test";

    private final LocalStackContainer localStack;
    private final AwsProperties.Tables tables;
    private final String reservationQueue;
    private final String paymentQueue;
    private final TestJwtIssuer issuer;
    private final PaymentGatewayStub paymentGateway;

    private EndToEndEnvironment() {
        String prefix = "e2e-" + UUID.randomUUID().toString().substring(0, 8);
        this.tables = DynamoDbLocalContainer.uniqueTables();
        this.reservationQueue = prefix + "-order-reservation";
        this.paymentQueue = prefix + "-order-payment";
        this.localStack = new LocalStackContainer(LOCALSTACK_IMAGE).withServices("sqs");
        this.localStack.start();
        try (SqsAsyncClient sqs = sqsClient()) {
            createQueueWithDeadLetterQueue(sqs, reservationQueue);
            createQueueWithDeadLetterQueue(sqs, paymentQueue);
        }
        this.issuer = TestJwtIssuer.create();
        this.paymentGateway = PaymentGatewayStub.start(issuer.jwkSetJson());
    }

    public static EndToEndEnvironment start() {
        return new EndToEndEnvironment();
    }

    public static AwsCredentialsProvider dummyCredentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(DUMMY_CREDENTIAL, DUMMY_CREDENTIAL));
    }

    public void register(DynamicPropertyRegistry registry) {
        registry.add("aws.region", localStack::getRegion);
        registry.add("aws.endpoint-override.dynamodb-endpoint", () -> DynamoDbLocalContainer.endpoint().toString());
        registry.add("aws.endpoint-override.sqs-endpoint", () -> localStack.getEndpoint().toString());
        registry.add("aws.dynamodb.tables.events", tables::events);
        registry.add("aws.dynamodb.tables.tickets", tables::tickets);
        registry.add("aws.dynamodb.tables.orders", tables::orders);
        registry.add("aws.dynamodb.tables.ticket-transitions", tables::ticketTransitions);
        registry.add("aws.dynamodb.tables.distributed-locks", tables::distributedLocks);
        registry.add("aws.dynamodb.tables.schema-migrations", tables::schemaMigrations);
        registry.add("aws.sqs.order-reservation-queue", () -> reservationQueue);
        registry.add("aws.sqs.order-payment-queue", () -> paymentQueue);
        registry.add("ticketing.payment.base-url", paymentGateway::baseUrl);
        registry.add("ticketing.payment.api-key", () -> PAYMENT_API_KEY);
        registry.add("ticketing.security.issuer", () -> TestJwtIssuer.ISSUER);
        registry.add("ticketing.security.audience", () -> TestJwtIssuer.AUDIENCE);
        registry.add("ticketing.security.roles-claim", () -> TestJwtIssuer.ROLES_CLAIM);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestJwtIssuer.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> paymentGateway.baseUrl() + "/jwks");
    }

    public TestJwtIssuer issuer() {
        return issuer;
    }

    public PaymentGatewayStub paymentGateway() {
        return paymentGateway;
    }

    public int deadLetters(String queueName) {
        try (SqsAsyncClient sqs = sqsClient()) {
            String url = sqs.getQueueUrl(request -> request.queueName(queueName + "-dlq")).join().queueUrl();
            Map<QueueAttributeName, String> attributes = sqs.getQueueAttributes(request -> request.queueUrl(url)
                            .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
                    .join().attributes();
            return Integer.parseInt(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                    + Integer.parseInt(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
        }
    }

    public String reservationQueue() {
        return reservationQueue;
    }

    public String paymentQueue() {
        return paymentQueue;
    }

    private SqsAsyncClient sqsClient() {
        return SqsAsyncClient.builder()
                .endpointOverride(localStack.getEndpoint())
                .region(Region.of(localStack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localStack.getAccessKey(), localStack.getSecretKey())))
                .httpClientBuilder(NettyNioAsyncHttpClient.builder())
                .build();
    }

    private static void createQueueWithDeadLetterQueue(SqsAsyncClient sqs, String name) {
        String dlqUrl = sqs.createQueue(request -> request.queueName(name + "-dlq")).join().queueUrl();
        String dlqArn = sqs.getQueueAttributes(request -> request.queueUrl(dlqUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN))
                .join().attributes().get(QueueAttributeName.QUEUE_ARN);
        String redrivePolicy = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\""
                + MAX_RECEIVE_COUNT + "\"}";
        sqs.createQueue(request -> request.queueName(name).attributes(Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT, "60",
                QueueAttributeName.REDRIVE_POLICY, redrivePolicy))).join();
    }
}
