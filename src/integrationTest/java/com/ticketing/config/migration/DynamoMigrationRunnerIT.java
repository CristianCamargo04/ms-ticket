package com.ticketing.config.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.config.properties.AwsProperties;
import com.ticketing.persistence.support.DynamoDbLocalContainer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexDescription;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

class DynamoMigrationRunnerIT {
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final List<String> MIGRATION_IDS = List.of("20261010100000_TCK-001", "20261010100100_TCK-002",
            "20261010100200_TCK-003", "20261010100300_TCK-004", "20261010100400_TCK-005");

    private final DynamoDbAsyncClient client = DynamoDbLocalContainer.client();

    @Test
    void shouldCreateEveryTableFromTheMigrationFiles() {
        AwsProperties.Tables tables = DynamoDbLocalContainer.uniqueTables();

        StepVerifier.create(DynamoDbLocalContainer.migrationRunner(tables).migrate())
                .expectNext(MIGRATION_IDS)
                .verifyComplete();

        TableDescription events = describe(tables.events());
        assertThat(keyNames(events.keySchema())).containsExactly("eventId");
        assertThat(events.globalSecondaryIndexes()).singleElement().satisfies(index -> {
            assertThat(index.indexName()).isEqualTo("status-eventDate-index");
            assertThat(keyNames(index.keySchema())).containsExactly("status", "eventDate");
            assertThat(index.projection().projectionType()).isEqualTo(ProjectionType.ALL);
        });
        GlobalSecondaryIndexDescription availability = describe(tables.tickets()).globalSecondaryIndexes().getFirst();
        assertThat(availability.indexName()).isEqualTo("eventId-statusKey-index");
        assertThat(availability.projection().projectionType()).isEqualTo(ProjectionType.INCLUDE);
        assertThat(availability.projection().nonKeyAttributes()).containsExactlyInAnyOrder("status", "version",
                "updatedAt");
        assertThat(describe(tables.orders()).globalSecondaryIndexes()).extracting(
                GlobalSecondaryIndexDescription::indexName).containsExactly("deadline-index");
        assertThat(keyNames(describe(tables.ticketTransitions()).keySchema()))
                .containsExactly("ticketKey", "transitionKey");
        assertThat(keyNames(describe(tables.distributedLocks()).keySchema())).containsExactly("lockName");
        assertTimeToLiveEnabled(tables);

        Map<String, String> recorded = recordedChecksums(tables);
        assertThat(recorded).containsOnlyKeys(MIGRATION_IDS);
        new MigrationChangelogReader(new DefaultResourceLoader(), MigrationChangelogReader.DEFAULT_CHANGELOG).read()
                .forEach(migration -> assertThat(recorded.get(migration.id())).isEqualTo(migration.checksum()));
    }

    @Test
    void shouldBeIdempotent() {
        AwsProperties.Tables tables = DynamoDbLocalContainer.uniqueTables();
        DynamoMigrationRunner runner = DynamoDbLocalContainer.migrationRunner(tables);
        runner.afterSingletonsInstantiated();

        StepVerifier.create(runner.migrate()).expectNext(List.of()).verifyComplete();
        assertThat(recordedChecksums(tables)).hasSize(MIGRATION_IDS.size());
    }

    @Test
    void shouldFailFastWhenAnAppliedMigrationChanged() {
        AwsProperties.Tables tables = DynamoDbLocalContainer.uniqueTables();
        DynamoDbLocalContainer.migrate(tables);
        client.putItem(PutItemRequest.builder()
                .tableName(tables.schemaMigrations())
                .item(Map.of("migrationId", AttributeValue.fromS(MIGRATION_IDS.get(1)),
                        "checksum", AttributeValue.fromS("tampered"),
                        "appliedAt", AttributeValue.fromN("0")))
                .build()).join();

        assertThatThrownBy(() -> DynamoDbLocalContainer.migrationRunner(tables).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Checksum mismatch")
                .hasMessageContaining(MIGRATION_IDS.get(1));
    }

    @Test
    void shouldSkipATableThatAlreadyExistsAndStillEnableItsTimeToLive() {
        AwsProperties.Tables tables = DynamoDbLocalContainer.uniqueTables();
        createLocksTableWithoutTimeToLive(tables);

        StepVerifier.create(DynamoDbLocalContainer.migrationRunner(tables).migrate())
                .expectNext(MIGRATION_IDS)
                .expectComplete()
                .verify(TIMEOUT);
        assertThat(recordedChecksums(tables)).containsOnlyKeys(MIGRATION_IDS);
        assertTimeToLiveEnabled(tables);
    }

    @Test
    void shouldKeepATimeToLiveThatIsAlreadyEnabled() {
        AwsProperties.Tables tables = DynamoDbLocalContainer.uniqueTables();
        createLocksTableWithoutTimeToLive(tables);
        client.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                .tableName(tables.distributedLocks())
                .timeToLiveSpecification(TimeToLiveSpecification.builder()
                        .attributeName("expiresAt")
                        .enabled(true)
                        .build())
                .build()).join();

        StepVerifier.create(DynamoDbLocalContainer.migrationRunner(tables).migrate())
                .expectNext(MIGRATION_IDS)
                .expectComplete()
                .verify(TIMEOUT);
        assertTimeToLiveEnabled(tables);
    }

    private void createLocksTableWithoutTimeToLive(AwsProperties.Tables tables) {
        client.createTable(CreateTableRequest.builder()
                .tableName(tables.distributedLocks())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("lockName")
                        .attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("lockName").keyType(KeyType.HASH).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build()).join();
    }

    private void assertTimeToLiveEnabled(AwsProperties.Tables tables) {
        assertThat(client.describeTimeToLive(DescribeTimeToLiveRequest.builder()
                .tableName(tables.distributedLocks()).build()).join().timeToLiveDescription())
                .satisfies(ttl -> {
                    assertThat(ttl.attributeName()).isEqualTo("expiresAt");
                    assertThat(ttl.timeToLiveStatus()).isIn(TimeToLiveStatus.ENABLED, TimeToLiveStatus.ENABLING);
                });
    }

    private TableDescription describe(String table) {
        return client.describeTable(DescribeTableRequest.builder().tableName(table).build()).join().table();
    }

    private Map<String, String> recordedChecksums(AwsProperties.Tables tables) {
        return client.scan(ScanRequest.builder().tableName(tables.schemaMigrations()).consistentRead(true).build())
                .join().items().stream()
                .collect(Collectors.toMap(item -> item.get("migrationId").s(),
                        item -> item.get("checksum").s()));
    }

    private static List<String> keyNames(List<KeySchemaElement> keySchema) {
        return keySchema.stream().map(KeySchemaElement::attributeName).toList();
    }
}
