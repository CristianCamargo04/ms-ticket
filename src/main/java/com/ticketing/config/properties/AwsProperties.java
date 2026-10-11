package com.ticketing.config.properties;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AWS settings bound from {@code aws.*}. There are deliberately no credentials here: the SDK uses
 * {@code DefaultCredentialsProvider} (environment variables locally, the IAM task role in AWS).
 *
 * @param region           AWS region
 * @param endpointOverride optional per-service endpoints (DynamoDB Local, LocalStack); never {@code null}, its
 *                         fields are {@code null} when not overridden
 * @param dynamodb         DynamoDB settings
 * @param sqs              SQS settings
 */
@ConfigurationProperties("aws")
public record AwsProperties(
        @DefaultValue("us-east-1") String region,
        @DefaultValue EndpointOverride endpointOverride,
        @DefaultValue Dynamodb dynamodb,
        @DefaultValue Sqs sqs) {

    /**
     * Optional endpoint overrides ({@code aws.endpoint-override.*}).
     *
     * @param dynamodbEndpoint DynamoDB endpoint, {@code null} for the regional AWS endpoint
     * @param sqsEndpoint      SQS endpoint, {@code null} for the regional AWS endpoint
     */
    public record EndpointOverride(URI dynamodbEndpoint, URI sqsEndpoint) {
    }

    /**
     * DynamoDB settings ({@code aws.dynamodb.*}).
     *
     * @param tables         physical table names
     * @param deadlineShards number of shards of the order deadline index partition key
     */
    public record Dynamodb(
            @DefaultValue Tables tables,
            @DefaultValue("8") int deadlineShards) {
    }

    /**
     * Physical DynamoDB table names ({@code aws.dynamodb.tables.*}); the migration files use the logical names
     * {@code events}, {@code tickets}, {@code orders}, {@code ticket-transitions}, {@code distributed-locks}.
     *
     * @param events            events table
     * @param tickets           tickets table
     * @param orders            orders table
     * @param ticketTransitions ticket transitions (audit) table
     * @param distributedLocks  distributed locks table
     * @param schemaMigrations  applied migrations table (local/ci only)
     */
    public record Tables(
            @DefaultValue("ticketing-events") String events,
            @DefaultValue("ticketing-tickets") String tickets,
            @DefaultValue("ticketing-orders") String orders,
            @DefaultValue("ticketing-ticket-transitions") String ticketTransitions,
            @DefaultValue("ticketing-distributed-locks") String distributedLocks,
            @DefaultValue("ticketing-schema-migrations") String schemaMigrations) {
    }

    /**
     * SQS settings ({@code aws.sqs.*}).
     *
     * @param orderReservationQueue name of the order reservation queue
     * @param orderPaymentQueue     name of the order payment queue
     */
    public record Sqs(
            @DefaultValue("ticketing-order-reservation") String orderReservationQueue,
            @DefaultValue("ticketing-order-payment") String orderPaymentQueue) {
    }
}
