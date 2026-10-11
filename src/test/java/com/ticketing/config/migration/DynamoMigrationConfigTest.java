package com.ticketing.config.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.config.properties.AwsProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.CreateTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveResponse;

class DynamoMigrationConfigTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AwsProperties.class)
    static class PropertiesConfig {
    }

    private final DynamoDbAsyncClient dynamoDb = mock(DynamoDbAsyncClient.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, DynamoMigrationConfig.class)
            .withBean(DynamoDbAsyncClient.class, () -> dynamoDb)
            .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void shouldNotRegisterTheRunnerByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DynamoMigrationRunner.class);
            assertThat(context).doesNotHaveBean(MigrationChangelogReader.class);
        });
        verify(dynamoDb, never()).createTable(any(CreateTableRequest.class));
        verify(dynamoDb, never()).scan(any(ScanRequest.class));
    }

    @Test
    void shouldNotRegisterTheRunnerWhenDisabled() {
        contextRunner.withPropertyValues("ticketing.migrations.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DynamoMigrationRunner.class));
    }

    @Test
    void shouldApplyTheMigrationsToTheConfiguredTablesAtStartupWhenEnabled() {
        when(dynamoDb.createTable(any(CreateTableRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(CreateTableResponse.builder().build()));
        when(dynamoDb.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DescribeTableResponse.builder()
                        .table(TableDescription.builder().tableStatus(TableStatus.ACTIVE).build()).build()));
        when(dynamoDb.scan(any(ScanRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(ScanResponse.builder().build()));
        when(dynamoDb.updateTimeToLive(any(UpdateTimeToLiveRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(UpdateTimeToLiveResponse.builder().build()));
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        contextRunner
                .withPropertyValues("ticketing.migrations.enabled=true",
                        "aws.dynamodb.tables.events=ci-events",
                        "aws.dynamodb.tables.schema-migrations=ci-migrations")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DynamoMigrationRunner.class);
                    assertThat(context).hasSingleBean(MigrationChangelogReader.class);
                });

        ArgumentCaptor<CreateTableRequest> creates = ArgumentCaptor.forClass(CreateTableRequest.class);
        verify(dynamoDb, times(6)).createTable(creates.capture());
        assertThat(creates.getAllValues()).extracting(CreateTableRequest::tableName).containsExactly(
                "ci-migrations", "ci-events", "ticketing-tickets", "ticketing-orders", "ticketing-ticket-transitions",
                "ticketing-distributed-locks");
    }
}
