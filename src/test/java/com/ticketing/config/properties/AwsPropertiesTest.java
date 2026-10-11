package com.ticketing.config.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;

class AwsPropertiesTest {

    private static void assertDefaultNames(AwsProperties properties) {
        assertThat(properties.dynamodb().tables().events()).isEqualTo("ticketing-events");
        assertThat(properties.dynamodb().tables().tickets()).isEqualTo("ticketing-tickets");
        assertThat(properties.dynamodb().tables().orders()).isEqualTo("ticketing-orders");
        assertThat(properties.dynamodb().tables().ticketTransitions()).isEqualTo("ticketing-ticket-transitions");
        assertThat(properties.dynamodb().tables().distributedLocks()).isEqualTo("ticketing-distributed-locks");
        assertThat(properties.dynamodb().tables().schemaMigrations()).isEqualTo("ticketing-schema-migrations");
        assertThat(properties.dynamodb().deadlineShards()).isEqualTo(8);
        assertThat(properties.sqs().orderReservationQueue()).isEqualTo("ticketing-order-reservation");
        assertThat(properties.sqs().orderPaymentQueue()).isEqualTo("ticketing-order-payment");
    }

    @Test
    void shouldApplyDefaultsWithoutEndpointOverrides() {
        AwsProperties properties = new Binder().bindOrCreate("aws", AwsProperties.class);

        assertDefaultNames(properties);
        assertThat(properties.region()).isEqualTo("us-east-1");
        assertThat(properties.endpointOverride()).isNotNull();
        assertThat(properties.endpointOverride().dynamodbEndpoint()).isNull();
        assertThat(properties.endpointOverride().sqsEndpoint()).isNull();
    }

    @Test
    void shouldNotOverrideEndpointsInTheCommonConfiguration() {
        AwsProperties properties = YamlBinding.binderFor().bindOrCreate("aws", AwsProperties.class);

        assertDefaultNames(properties);
        assertThat(properties.endpointOverride().dynamodbEndpoint()).isNull();
        assertThat(properties.endpointOverride().sqsEndpoint()).isNull();
    }

    @Test
    void shouldPointLocalProfileToDynamoDbLocalAndLocalStack() {
        AwsProperties properties = YamlBinding.binderForProfile("local")
                .bindOrCreate("aws", AwsProperties.class);

        assertDefaultNames(properties);
        assertThat(properties.endpointOverride().dynamodbEndpoint()).isEqualTo(URI.create("http://localhost:8000"));
        assertThat(properties.endpointOverride().sqsEndpoint()).isEqualTo(URI.create("http://localhost:4566"));
    }
}
