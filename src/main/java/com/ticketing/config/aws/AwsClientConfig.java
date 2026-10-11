package com.ticketing.config.aws;

import com.ticketing.config.properties.AwsProperties;
import java.net.URI;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsAsyncClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Asynchronous AWS SDK v2 clients on the non-blocking Netty HTTP client.
 *
 * <ul>
 *   <li>Region from {@code aws.region}; optional per-service endpoint overrides ({@code aws.endpoint-override.*})
 *   for DynamoDB Local and LocalStack.</li>
 *   <li>Credentials from the {@link DefaultCredentialsProvider} chain (environment variables locally, the IAM task
 *   role in AWS); never from configuration.</li>
 *   <li>The SDK {@link RetryMode#STANDARD standard} retry strategy (exponential backoff with jitter, throttling
 *   aware) plus explicit API call timeouts, so that no call can hang a reactive pipeline.</li>
 * </ul>
 * Each client owns its Netty HTTP client and closes it with the client (Spring infers {@code close()} as the
 * destroy method).
 */
@Configuration(proxyBeanMethods = false)
public class AwsClientConfig {

    /** Upper bound of a DynamoDB call, including the SDK retries. */
    static final Duration DYNAMODB_API_CALL_TIMEOUT = Duration.ofSeconds(10);

    /** Upper bound of a single DynamoDB attempt. */
    static final Duration DYNAMODB_API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(3);

    /** Concurrent DynamoDB connections: inventory transactions are the hot path. */
    static final int DYNAMODB_MAX_CONCURRENCY = 200;

    /**
     * Upper bound of an SQS call, including the SDK retries. SQS calls are long-polling receives (up to 20 s) on
     * the worker side; publishers override these timeouts per request with much shorter ones.
     */
    static final Duration SQS_API_CALL_TIMEOUT = Duration.ofSeconds(60);

    /** Upper bound of a single SQS attempt: longer than the maximum long-polling wait (20 s). */
    static final Duration SQS_API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(25);

    /** Concurrent SQS connections (pollers, acknowledgements and publications). */
    static final int SQS_MAX_CONCURRENCY = 100;

    /** TCP connect timeout of both HTTP clients. */
    static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(2);

    /** Maximum wait for a pooled connection before the attempt fails. */
    static final Duration CONNECTION_ACQUISITION_TIMEOUT = Duration.ofSeconds(5);

    /**
     * The AWS credentials chain, shared by every client and closed with the application context.
     *
     * @return the default credentials provider
     */
    @Bean
    public AwsCredentialsProvider awsCredentialsProvider() {
        return DefaultCredentialsProvider.builder().build();
    }

    /**
     * Asynchronous DynamoDB client.
     *
     * @param properties          AWS settings
     * @param credentialsProvider AWS credentials
     * @return the client
     */
    @Bean
    public DynamoDbAsyncClient dynamoDbAsyncClient(AwsProperties properties,
                                                   AwsCredentialsProvider credentialsProvider) {
        return configure(DynamoDbAsyncClient.builder(), properties, credentialsProvider,
                properties.endpointOverride().dynamodbEndpoint(),
                DYNAMODB_MAX_CONCURRENCY, DYNAMODB_API_CALL_TIMEOUT, DYNAMODB_API_CALL_ATTEMPT_TIMEOUT)
                .build();
    }

    /**
     * Asynchronous SQS client.
     *
     * @param properties          AWS settings
     * @param credentialsProvider AWS credentials
     * @return the client
     */
    @Bean
    public SqsAsyncClient sqsAsyncClient(AwsProperties properties, AwsCredentialsProvider credentialsProvider) {
        return configure(SqsAsyncClient.builder(), properties, credentialsProvider,
                properties.endpointOverride().sqsEndpoint(),
                SQS_MAX_CONCURRENCY, SQS_API_CALL_TIMEOUT, SQS_API_CALL_ATTEMPT_TIMEOUT)
                .build();
    }

    private static <B extends AwsClientBuilder<B, ?> & AwsAsyncClientBuilder<B, ?>> B configure(
            B builder, AwsProperties properties, AwsCredentialsProvider credentialsProvider, URI endpointOverride,
            int maxConcurrency, Duration apiCallTimeout, Duration apiCallAttemptTimeout) {
        builder.region(Region.of(properties.region()))
                .credentialsProvider(credentialsProvider)
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .maxConcurrency(maxConcurrency)
                        .connectionTimeout(CONNECTION_TIMEOUT)
                        .connectionAcquisitionTimeout(CONNECTION_ACQUISITION_TIMEOUT))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(apiCallTimeout)
                        .apiCallAttemptTimeout(apiCallAttemptTimeout)
                        .retryStrategy(RetryMode.STANDARD)
                        .build());
        if (endpointOverride != null) {
            builder.endpointOverride(endpointOverride);
        }
        return builder;
    }
}
