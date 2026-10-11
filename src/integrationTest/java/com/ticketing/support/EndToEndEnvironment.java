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

/**
 * Everything an in-process instance of the whole application needs, started once per test class (test code:
 * blocking {@code join()} is fine here):
 *
 * <ul>
 *   <li>DynamoDB Local ({@code amazon/dynamodb-local:latest}, shared per JVM) with <b>unique table names</b>: the
 *       application creates them itself through its migration runner ({@code ticketing.migrations.enabled=true});</li>
 *   <li>LocalStack 4.9 SQS with unique {@code order-reservation} / {@code order-payment} queues, each with its
 *       dead-letter queue and redrive policy ({@code maxReceiveCount=5}, like {@code init-sqs.sh});</li>
 *   <li>the {@link PaymentGatewayStub} (charges + JWK set) and the {@link TestJwtIssuer}.</li>
 * </ul>
 * Containers are reaped by Testcontainers when the JVM exits.
 */
public final class EndToEndEnvironment {

    /** Pinned: {@code latest} requires a paid auth token. */
    public static final DockerImageName LOCALSTACK_IMAGE = DockerImageName.parse("localstack/localstack:4.9");

    /** API key the application must send to the payment gateway. */
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

    /**
     * Starts the containers, creates the queues and starts the stubs.
     *
     * @return the running environment
     */
    public static EndToEndEnvironment start() {
        return new EndToEndEnvironment();
    }

    /**
     * Dummy AWS credentials for DynamoDB Local and LocalStack (they accept any key), so the application's default
     * credentials chain never reaches a developer's real AWS profile.
     *
     * @return static dummy credentials
     */
    public static AwsCredentialsProvider dummyCredentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(DUMMY_CREDENTIAL, DUMMY_CREDENTIAL));
    }

    /**
     * Points the application at the environment: endpoints, unique table and queue names, payment gateway (with its
     * API key) and JWT issuer ({@code cognito:groups} roles, {@code aud} or {@code client_id} audience).
     *
     * @param registry the dynamic properties of the test context
     */
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

    /**
     * The token issuer trusted by the application.
     *
     * @return the issuer
     */
    public TestJwtIssuer issuer() {
        return issuer;
    }

    /**
     * The payment gateway stub.
     *
     * @return the stub
     */
    public PaymentGatewayStub paymentGateway() {
        return paymentGateway;
    }

    /**
     * Visible plus in-flight messages of a dead-letter queue of the environment.
     *
     * @param queueName the source queue name (its DLQ is {@code <name>-dlq})
     * @return the number of dead letters
     */
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

    /**
     * Name of the order-reservation queue.
     *
     * @return the queue name
     */
    public String reservationQueue() {
        return reservationQueue;
    }

    /**
     * Name of the order-payment queue.
     *
     * @return the queue name
     */
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
