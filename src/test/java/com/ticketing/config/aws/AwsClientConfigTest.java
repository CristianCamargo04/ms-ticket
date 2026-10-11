package com.ticketing.config.aws;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.config.properties.AwsProperties;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbServiceClientConfiguration;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsServiceClientConfiguration;

class AwsClientConfigTest {
    private static final URI DYNAMODB_LOCAL = URI.create("http://localhost:8000");
    private static final URI LOCALSTACK = URI.create("http://localhost:4566");

    private final AwsClientConfig config = new AwsClientConfig();
    private final AwsCredentialsProvider credentials =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AwsProperties.class)
    static class PropertiesConfig {
    }

    private static AwsProperties properties(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("aws", AwsProperties.class);
    }

    @Test
    void shouldBuildRegionalClientsWithTimeoutsAndTheStandardRetryStrategy() {
        AwsProperties properties = properties(Map.of("aws.region", "eu-west-1"));

        try (DynamoDbAsyncClient dynamoDb = config.dynamoDbAsyncClient(properties, credentials);
             SqsAsyncClient sqs = config.sqsAsyncClient(properties, credentials)) {
            DynamoDbServiceClientConfiguration dynamoDbConfiguration = dynamoDb.serviceClientConfiguration();
            SqsServiceClientConfiguration sqsConfiguration = sqs.serviceClientConfiguration();
            ClientOverrideConfiguration dynamoDbOverrides = dynamoDbConfiguration.overrideConfiguration();
            ClientOverrideConfiguration sqsOverrides = sqsConfiguration.overrideConfiguration();

            assertThat(dynamoDbConfiguration.region()).isEqualTo(Region.EU_WEST_1);
            assertThat(dynamoDbConfiguration.endpointOverride()).isEmpty();
            assertThat(dynamoDbConfiguration.credentialsProvider()).isSameAs(credentials);
            assertThat(sqsConfiguration.region()).isEqualTo(Region.EU_WEST_1);
            assertThat(sqsConfiguration.endpointOverride()).isEmpty();
            assertThat(sqsConfiguration.credentialsProvider()).isSameAs(credentials);

            assertThat(dynamoDbOverrides.apiCallTimeout()).contains(AwsClientConfig.DYNAMODB_API_CALL_TIMEOUT);
            assertThat(dynamoDbOverrides.apiCallAttemptTimeout())
                    .contains(AwsClientConfig.DYNAMODB_API_CALL_ATTEMPT_TIMEOUT);
            assertThat(dynamoDbOverrides.retryStrategy())
                    .hasValueSatisfying(strategy -> assertThat(strategy.maxAttempts()).isEqualTo(3));
            assertThat(sqsOverrides.apiCallTimeout()).contains(AwsClientConfig.SQS_API_CALL_TIMEOUT);
            assertThat(sqsOverrides.apiCallAttemptTimeout()).contains(AwsClientConfig.SQS_API_CALL_ATTEMPT_TIMEOUT);
            assertThat(sqsOverrides.retryStrategy())
                    .hasValueSatisfying(strategy -> assertThat(strategy.maxAttempts()).isEqualTo(3));
        }
    }

    @Test
    void shouldApplyPerServiceEndpointOverrides() {
        AwsProperties properties = properties(Map.of(
                "aws.region", "us-east-1",
                "aws.endpoint-override.dynamodb-endpoint", DYNAMODB_LOCAL.toString(),
                "aws.endpoint-override.sqs-endpoint", LOCALSTACK.toString()));

        try (DynamoDbAsyncClient dynamoDb = config.dynamoDbAsyncClient(properties, credentials);
             SqsAsyncClient sqs = config.sqsAsyncClient(properties, credentials)) {
            assertThat(dynamoDb.serviceClientConfiguration().region()).isEqualTo(Region.US_EAST_1);
            assertThat(dynamoDb.serviceClientConfiguration().endpointOverride()).contains(DYNAMODB_LOCAL);
            assertThat(sqs.serviceClientConfiguration().endpointOverride()).contains(LOCALSTACK);
        }
    }

    @Test
    void shouldOverrideOnlyTheConfiguredEndpoint() {
        AwsProperties properties = properties(Map.of("aws.endpoint-override.sqs-endpoint", LOCALSTACK.toString()));

        try (DynamoDbAsyncClient dynamoDb = config.dynamoDbAsyncClient(properties, credentials);
             SqsAsyncClient sqs = config.sqsAsyncClient(properties, credentials)) {
            assertThat(dynamoDb.serviceClientConfiguration().endpointOverride()).isEmpty();
            assertThat(sqs.serviceClientConfiguration().endpointOverride()).contains(LOCALSTACK);
        }
    }

    @Test
    void shouldAllowSqsLongPollingWithinASingleAttempt() {
        assertThat(AwsClientConfig.SQS_API_CALL_ATTEMPT_TIMEOUT).isGreaterThan(Duration.ofSeconds(20));
        assertThat(AwsClientConfig.SQS_API_CALL_TIMEOUT).isGreaterThan(AwsClientConfig.SQS_API_CALL_ATTEMPT_TIMEOUT);
        assertThat(AwsClientConfig.DYNAMODB_API_CALL_TIMEOUT)
                .isGreaterThan(AwsClientConfig.DYNAMODB_API_CALL_ATTEMPT_TIMEOUT);
    }

    @Test
    void shouldUseTheDefaultCredentialsChain() {
        AwsCredentialsProvider provider = config.awsCredentialsProvider();

        assertThat(provider).isInstanceOf(DefaultCredentialsProvider.class);
    }

    @Test
    void shouldRegisterTheClientsAsBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class, AwsClientConfig.class)
                .withPropertyValues("aws.region=sa-east-1", "aws.endpoint-override.sqs-endpoint=" + LOCALSTACK)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AwsCredentialsProvider.class);
                    assertThat(context.getBean(DynamoDbAsyncClient.class).serviceClientConfiguration().region())
                            .isEqualTo(Region.SA_EAST_1);
                    assertThat(context.getBean(SqsAsyncClient.class).serviceClientConfiguration().endpointOverride())
                            .contains(LOCALSTACK);
                });
    }
}
