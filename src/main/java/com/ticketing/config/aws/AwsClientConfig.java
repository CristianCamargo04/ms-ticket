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

@Configuration(proxyBeanMethods = false)
public class AwsClientConfig {
    static final Duration DYNAMODB_API_CALL_TIMEOUT = Duration.ofSeconds(10);

    static final Duration DYNAMODB_API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(3);

    static final int DYNAMODB_MAX_CONCURRENCY = 200;

    static final Duration SQS_API_CALL_TIMEOUT = Duration.ofSeconds(60);

    static final Duration SQS_API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(25);

    static final int SQS_MAX_CONCURRENCY = 100;

    static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(2);

    static final Duration CONNECTION_ACQUISITION_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public AwsCredentialsProvider awsCredentialsProvider() {
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean
    public DynamoDbAsyncClient dynamoDbAsyncClient(AwsProperties properties,
                                                   AwsCredentialsProvider credentialsProvider) {
        return configure(DynamoDbAsyncClient.builder(), properties, credentialsProvider,
                properties.endpointOverride().dynamodbEndpoint(),
                DYNAMODB_MAX_CONCURRENCY, DYNAMODB_API_CALL_TIMEOUT, DYNAMODB_API_CALL_ATTEMPT_TIMEOUT)
                .build();
    }

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
