package com.ticketing.config.migration;

import com.ticketing.config.properties.AwsProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("ticketing.migrations.enabled")
public class DynamoMigrationConfig {
    @Bean
    public MigrationChangelogReader migrationChangelogReader(ResourceLoader resourceLoader) {
        return new MigrationChangelogReader(resourceLoader, MigrationChangelogReader.DEFAULT_CHANGELOG);
    }

    @Bean
    public DynamoMigrationRunner dynamoMigrationRunner(DynamoDbAsyncClient dynamoDbAsyncClient,
                                                       MigrationChangelogReader migrationChangelogReader,
                                                       AwsProperties awsProperties, Clock clock) {
        AwsProperties.Tables tables = awsProperties.dynamodb().tables();
        return new DynamoMigrationRunner(dynamoDbAsyncClient, migrationChangelogReader,
                TableDefinitionParser.forTables(tables), tables.schemaMigrations(), clock);
    }
}
