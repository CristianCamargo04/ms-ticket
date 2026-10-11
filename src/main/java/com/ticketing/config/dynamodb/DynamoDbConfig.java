package com.ticketing.config.dynamodb;

import com.ticketing.config.properties.AwsProperties;
import com.ticketing.persistence.impl.support.DynamoDbTableNames;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * DynamoDB wiring of the persistence layer: the enhanced async client (built on the {@link DynamoDbAsyncClient} bean
 * of {@code config.aws.AwsClientConfig}) and the {@link DynamoDbTableNames} settings record derived from
 * {@code aws.dynamodb.*}.
 */
@Configuration(proxyBeanMethods = false)
public class DynamoDbConfig {

    /**
     * Enhanced client sharing the low-level client (same HTTP client, credentials and endpoint).
     *
     * @param dynamoDbAsyncClient the low-level client
     * @return the enhanced client
     */
    @Bean
    public DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient(DynamoDbAsyncClient dynamoDbAsyncClient) {
        return DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(dynamoDbAsyncClient).build();
    }

    /**
     * Physical table names and deadline index shards used by the persistence adapters.
     *
     * @param properties AWS settings
     * @return the persistence settings
     */
    @Bean
    public DynamoDbTableNames dynamoDbTableNames(AwsProperties properties) {
        AwsProperties.Tables tables = properties.dynamodb().tables();
        return new DynamoDbTableNames(tables.events(), tables.tickets(), tables.orders(), tables.ticketTransitions(),
                tables.distributedLocks(), properties.dynamodb().deadlineShards());
    }
}
