package com.ticketing.config.properties;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("aws")
public record AwsProperties(
        @DefaultValue("us-east-1") String region,
        @DefaultValue EndpointOverride endpointOverride,
        @DefaultValue Dynamodb dynamodb,
        @DefaultValue Sqs sqs) {
    public record EndpointOverride(URI dynamodbEndpoint, URI sqsEndpoint) {
    }

    public record Dynamodb(
            @DefaultValue Tables tables,
            @DefaultValue("8") int deadlineShards) {
    }

    public record Tables(
            @DefaultValue("ticketing-events") String events,
            @DefaultValue("ticketing-tickets") String tickets,
            @DefaultValue("ticketing-orders") String orders,
            @DefaultValue("ticketing-ticket-transitions") String ticketTransitions,
            @DefaultValue("ticketing-distributed-locks") String distributedLocks,
            @DefaultValue("ticketing-schema-migrations") String schemaMigrations) {
    }

    public record Sqs(
            @DefaultValue("ticketing-order-reservation") String orderReservationQueue,
            @DefaultValue("ticketing-order-payment") String orderPaymentQueue) {
    }
}
