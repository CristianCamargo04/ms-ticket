package com.ticketing.config.migration;

import com.ticketing.config.properties.AwsProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Registers the DynamoDB migration runner when {@code ticketing.migrations.enabled=true} (the {@code local} and
 * {@code ci} profiles). In AWS the property is {@code false}: Terraform owns the same table definitions.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("ticketing.migrations.enabled")
public class DynamoMigrationConfig {

    /**
     * Reader of the master changelog and of the migration files.
     *
     * @param resourceLoader the application resource loader
     * @return the reader
     */
    @Bean
    public MigrationChangelogReader migrationChangelogReader(ResourceLoader resourceLoader) {
        return new MigrationChangelogReader(resourceLoader, MigrationChangelogReader.DEFAULT_CHANGELOG);
    }

    /**
     * The runner; it applies the pending migrations once all singletons are created.
     *
     * @param dynamoDbAsyncClient      low-level DynamoDB client
     * @param migrationChangelogReader the changelog reader
     * @param awsProperties            AWS settings (physical table names)
     * @param clock                    application clock
     * @return the runner
     */
    @Bean
    public DynamoMigrationRunner dynamoMigrationRunner(DynamoDbAsyncClient dynamoDbAsyncClient,
                                                       MigrationChangelogReader migrationChangelogReader,
                                                       AwsProperties awsProperties, Clock clock) {
        AwsProperties.Tables tables = awsProperties.dynamodb().tables();
        return new DynamoMigrationRunner(dynamoDbAsyncClient, migrationChangelogReader,
                TableDefinitionParser.forTables(tables), tables.schemaMigrations(), clock);
    }
}
