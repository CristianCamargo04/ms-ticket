package com.ticketing.config.dynamodb;

import com.ticketing.config.properties.AwsProperties;
import com.ticketing.persistence.impl.support.DynamoDbTableNames;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

@Configuration(proxyBeanMethods = false)
public class DynamoDbConfig {
    @Bean
    public DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient(DynamoDbAsyncClient dynamoDbAsyncClient) {
        return DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(dynamoDbAsyncClient).build();
    }

    @Bean
    public DynamoDbTableNames dynamoDbTableNames(AwsProperties properties) {
        AwsProperties.Tables tables = properties.dynamodb().tables();
        return new DynamoDbTableNames(tables.events(), tables.tickets(), tables.orders(), tables.ticketTransitions(),
                tables.distributedLocks(), properties.dynamodb().deadlineShards());
    }
}
