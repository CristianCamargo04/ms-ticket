package com.ticketing.config.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.config.migration.MigrationChangelogReader.Migration;
import com.ticketing.config.migration.TableDefinitionParser.TableDefinition;
import com.ticketing.config.properties.AwsProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.CreateTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexDescription;
import software.amazon.awssdk.services.dynamodb.model.IndexStatus;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveDescription;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveResponse;
import software.amazon.awssdk.utils.builder.SdkBuilder;

@ExtendWith(MockitoExtension.class)
class DynamoMigrationRunnerTest {
    private static final AwsProperties.Tables TABLES = new AwsProperties.Tables("p-events", "p-tickets", "p-orders",
            "p-transitions", "p-locks", "p-migrations");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC);
    private static final MigrationChangelogReader READER = new MigrationChangelogReader(new DefaultResourceLoader(),
            MigrationChangelogReader.DEFAULT_CHANGELOG);
    private static final List<Migration> MIGRATIONS = READER.read();
    private static final List<String> IDS = MIGRATIONS.stream().map(Migration::id).toList();
    private static final String TTL_ATTRIBUTE = "expiresAt";

    @Mock
    private DynamoDbAsyncClient dynamoDb;

    private DynamoMigrationRunner runner;

    @BeforeEach
    void setUp() {
        runner = new DynamoMigrationRunner(dynamoDb, READER, TableDefinitionParser.forTables(TABLES), "p-migrations",
                CLOCK, Duration.ofMillis(100), 3, Duration.ofSeconds(5));
    }

    @Test
    void shouldApplyEveryPendingMigrationInOrder() {
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(done(CreateTableResponse.builder()));
        tablesAreActive();
        history();
        timeToLive(TimeToLiveStatus.DISABLED, null);
        when(dynamoDb.updateTimeToLive(any(UpdateTimeToLiveRequest.class)))
                .thenReturn(done(UpdateTimeToLiveResponse.builder()));
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(done(PutItemResponse.builder()));

        StepVerifier.create(runner.migrate()).expectNext(IDS).verifyComplete();

        ArgumentCaptor<CreateTableRequest> creates = ArgumentCaptor.forClass(CreateTableRequest.class);
        verify(dynamoDb, times(6)).createTable(creates.capture());
        assertThat(creates.getAllValues()).extracting(CreateTableRequest::tableName)
                .containsExactly("p-migrations", "p-events", "p-tickets", "p-orders", "p-transitions", "p-locks");
        CreateTableRequest historyTable = creates.getAllValues().getFirst();
        assertThat(historyTable.keySchema()).singleElement().satisfies(key -> {
            assertThat(key.attributeName()).isEqualTo("migrationId");
            assertThat(key.keyType()).isEqualTo(KeyType.HASH);
        });
        assertThat(historyTable.attributeDefinitions().getFirst().attributeType()).isEqualTo(ScalarAttributeType.S);
        assertThat(historyTable.billingMode()).isEqualTo(BillingMode.PAY_PER_REQUEST);

        ArgumentCaptor<DescribeTimeToLiveRequest> ttlStatus = ArgumentCaptor.forClass(DescribeTimeToLiveRequest.class);
        verify(dynamoDb).describeTimeToLive(ttlStatus.capture());
        assertThat(ttlStatus.getValue().tableName()).isEqualTo("p-locks");
        ArgumentCaptor<UpdateTimeToLiveRequest> ttl = ArgumentCaptor.forClass(UpdateTimeToLiveRequest.class);
        verify(dynamoDb).updateTimeToLive(ttl.capture());
        assertThat(ttl.getValue().tableName()).isEqualTo("p-locks");
        assertThat(ttl.getValue().timeToLiveSpecification().attributeName()).isEqualTo(TTL_ATTRIBUTE);
        assertThat(ttl.getValue().timeToLiveSpecification().enabled()).isTrue();

        ArgumentCaptor<PutItemRequest> records = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb, times(5)).putItem(records.capture());
        assertThat(records.getAllValues()).allSatisfy(record -> {
            assertThat(record.tableName()).isEqualTo("p-migrations");
            assertThat(record.conditionExpression()).isEqualTo("attribute_not_exists(migrationId)");
            assertThat(record.item().get("appliedAt").n()).isEqualTo(Long.toString(CLOCK.millis()));
        });
        assertThat(records.getAllValues()).extracting(record -> record.item().get("migrationId").s())
                .containsExactlyElementsOf(IDS);
        assertThat(records.getAllValues()).extracting(record -> record.item().get("checksum").s())
                .containsExactlyElementsOf(MIGRATIONS.stream().map(Migration::checksum).toList());

        ArgumentCaptor<ScanRequest> scan = ArgumentCaptor.forClass(ScanRequest.class);
        verify(dynamoDb).scan(scan.capture());
        assertThat(scan.getValue().tableName()).isEqualTo("p-migrations");
        assertThat(scan.getValue().consistentRead()).isTrue();
    }

    @Test
    void shouldSkipMigrationsThatWereAlreadyApplied() {
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(alreadyExists());
        tablesAreActive();
        history(MIGRATIONS.stream().map(migration -> record(migration.id(), migration.checksum())).toList());

        StepVerifier.create(runner.migrate()).expectNext(List.of()).verifyComplete();

        verify(dynamoDb, times(1)).createTable(any(CreateTableRequest.class));
        verify(dynamoDb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void shouldFailFastWhenAnAppliedMigrationChanged() {
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(alreadyExists());
        tablesAreActive();
        history(List.of(record(IDS.get(0), MIGRATIONS.get(0).checksum()), record(IDS.get(1), "edited")));

        StepVerifier.create(runner.migrate())
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Checksum mismatch")
                        .hasMessageContaining(IDS.get(1))
                        .hasMessageContaining("edited"))
                .verify();

        verify(dynamoDb, times(1)).createTable(any(CreateTableRequest.class));
    }

    @Test
    void shouldTreatAHistoryRecordWithoutChecksumAsAMismatch() {
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(alreadyExists());
        tablesAreActive();
        history(List.of(Map.of("migrationId", AttributeValue.fromS(IDS.get(0)))));

        StepVerifier.create(runner.migrate()).expectError(IllegalStateException.class).verify();
    }

    @ParameterizedTest
    @CsvSource({"ENABLED", "ENABLING"})
    void shouldSkipExistingTablesWhoseTimeToLiveIsInPlaceAndStillRecordTheirMigrations(TimeToLiveStatus status) {
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(alreadyExists());
        tablesAreActive();
        history();
        timeToLive(status, TTL_ATTRIBUTE);
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(done(PutItemResponse.builder()));

        StepVerifier.create(runner.migrate()).expectNext(IDS).verifyComplete();

        verify(dynamoDb).describeTimeToLive(any(DescribeTimeToLiveRequest.class));
        verify(dynamoDb, never()).updateTimeToLive(any(UpdateTimeToLiveRequest.class));
        verify(dynamoDb, times(5)).putItem(any(PutItemRequest.class));
    }

    @Test
    void shouldEnableTheTimeToLiveOfAnExistingTableBeforeRecordingItsMigration() {
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(alreadyExists());
        tablesAreActive();
        history();
        timeToLive(TimeToLiveStatus.DISABLED, null);
        when(dynamoDb.updateTimeToLive(any(UpdateTimeToLiveRequest.class)))
                .thenReturn(done(UpdateTimeToLiveResponse.builder()));
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(done(PutItemResponse.builder()));

        StepVerifier.create(runner.migrate()).expectNext(IDS).verifyComplete();

        InOrder order = inOrder(dynamoDb);
        order.verify(dynamoDb).putItem(argThat((PutItemRequest put) -> recordOf(put, IDS.get(3))));
        order.verify(dynamoDb).describeTimeToLive(any(DescribeTimeToLiveRequest.class));
        order.verify(dynamoDb).updateTimeToLive(any(UpdateTimeToLiveRequest.class));
        order.verify(dynamoDb).putItem(argThat((PutItemRequest put) -> recordOf(put, IDS.getLast())));
    }

    @Test
    void shouldTreatAMissingTimeToLiveDescriptionAsDisabled() {
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(alreadyExists());
        tablesAreActive();
        history();
        when(dynamoDb.describeTimeToLive(any(DescribeTimeToLiveRequest.class)))
                .thenReturn(done(DescribeTimeToLiveResponse.builder()));
        when(dynamoDb.updateTimeToLive(any(UpdateTimeToLiveRequest.class)))
                .thenReturn(done(UpdateTimeToLiveResponse.builder()));
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(done(PutItemResponse.builder()));

        StepVerifier.create(runner.migrate()).expectNext(IDS).verifyComplete();

        verify(dynamoDb).updateTimeToLive(any(UpdateTimeToLiveRequest.class));
    }

    @Test
    void shouldFailFastWhenAnotherTimeToLiveAttributeIsEnabled() {
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(alreadyExists());
        tablesAreActive();
        history();
        timeToLive(TimeToLiveStatus.ENABLED, "ttl");
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(done(PutItemResponse.builder()));

        StepVerifier.create(runner.migrate())
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("p-locks")
                        .hasMessageContaining("ttl")
                        .hasMessageContaining(TTL_ATTRIBUTE))
                .verify();

        verify(dynamoDb, never()).updateTimeToLive(any(UpdateTimeToLiveRequest.class));
        verify(dynamoDb, times(4)).putItem(any(PutItemRequest.class));
    }

    @Test
    void shouldAcceptATimeToLiveEnabledConcurrentlyByAnotherRunner() {
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(alreadyExists());
        tablesAreActive();
        history();
        when(dynamoDb.describeTimeToLive(any(DescribeTimeToLiveRequest.class)))
                .thenReturn(timeToLiveDescribed(TimeToLiveStatus.DISABLED, null))
                .thenReturn(timeToLiveDescribed(TimeToLiveStatus.ENABLED, TTL_ATTRIBUTE));
        when(dynamoDb.updateTimeToLive(any(UpdateTimeToLiveRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(validationError("TimeToLive is already enabled")));
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(done(PutItemResponse.builder()));

        StepVerifier.create(runner.migrate()).expectNext(IDS).verifyComplete();

        verify(dynamoDb, times(2)).describeTimeToLive(any(DescribeTimeToLiveRequest.class));
        verify(dynamoDb, times(5)).putItem(any(PutItemRequest.class));
    }

    @Test
    void shouldNotRecordAMigrationWhoseTimeToLiveCannotBeEnabled() {
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(alreadyExists());
        tablesAreActive();
        history();
        timeToLive(TimeToLiveStatus.DISABLING, TTL_ATTRIBUTE);
        DynamoDbException failure = validationError("Time to live has been modified multiple times");
        when(dynamoDb.updateTimeToLive(any(UpdateTimeToLiveRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(failure));
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(done(PutItemResponse.builder()));

        StepVerifier.create(runner.migrate())
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                .verify();

        verify(dynamoDb, times(2)).describeTimeToLive(any(DescribeTimeToLiveRequest.class));
        verify(dynamoDb, times(4)).putItem(any(PutItemRequest.class));
    }

    @ParameterizedTest
    @CsvSource({"ENABLED, 1", "ENABLING, 1", "DISABLED, 0", "DISABLING, 0"})
    void shouldConvergeADeclaredDisabledTimeToLive(TimeToLiveStatus current, int expectedUpdates) {
        TableDefinitionParser parser = mock(TableDefinitionParser.class);
        TimeToLiveSpecification disabled = TimeToLiveSpecification.builder()
                .attributeName(TTL_ATTRIBUTE)
                .enabled(false)
                .build();
        when(parser.parse(any())).thenReturn(new TableDefinition(
                CreateTableRequest.builder().tableName("p-locks").build(), disabled));
        DynamoMigrationRunner disabling = new DynamoMigrationRunner(dynamoDb, READER, parser, "p-migrations",
                CLOCK, Duration.ofMillis(100), 3, Duration.ofSeconds(5));
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(alreadyExists());
        tablesAreActive();
        history();
        timeToLive(current, TTL_ATTRIBUTE);
        if (expectedUpdates > 0) {
            when(dynamoDb.updateTimeToLive(any(UpdateTimeToLiveRequest.class)))
                    .thenReturn(done(UpdateTimeToLiveResponse.builder()));
        }
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(done(PutItemResponse.builder()));

        StepVerifier.create(disabling.migrate()).expectNext(IDS).verifyComplete();

        ArgumentCaptor<UpdateTimeToLiveRequest> updates = ArgumentCaptor.forClass(UpdateTimeToLiveRequest.class);
        verify(dynamoDb, times(expectedUpdates * IDS.size())).updateTimeToLive(updates.capture());
        assertThat(updates.getAllValues()).allSatisfy(update -> assertThat(update.timeToLiveSpecification())
                .isEqualTo(disabled));
    }

    @Test
    void shouldWaitUntilTheTableAndItsIndexesAreActive() {
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(alreadyExists());
        when(dynamoDb.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(described(TableStatus.CREATING))
                .thenReturn(CompletableFuture.failedFuture(ResourceNotFoundException.builder().message("x").build()))
                .thenReturn(described(TableStatus.ACTIVE, IndexStatus.CREATING))
                .thenReturn(described(TableStatus.ACTIVE, IndexStatus.ACTIVE));
        history(MIGRATIONS.stream().map(migration -> record(migration.id(), migration.checksum())).toList());

        StepVerifier.withVirtualTime(runner::migrate)
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(299))
                .thenAwait(Duration.ofMillis(1))
                .expectNext(List.of())
                .verifyComplete();

        verify(dynamoDb, times(4)).describeTable(any(DescribeTableRequest.class));
    }

    @Test
    void shouldGiveUpOnATableThatNeverBecomesActive() {
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(done(CreateTableResponse.builder()));
        when(dynamoDb.describeTable(any(DescribeTableRequest.class))).thenReturn(described(TableStatus.CREATING));

        StepVerifier.withVirtualTime(runner::migrate)
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(1))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("p-migrations did not become ACTIVE"))
                .verify();

        verify(dynamoDb, times(4)).describeTable(any(DescribeTableRequest.class));
    }

    @Test
    void shouldNotRetryUnexpectedStatusCheckFailures() {
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(alreadyExists());
        when(dynamoDb.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(InternalServerErrorException.builder().build()));

        StepVerifier.create(runner.migrate()).expectError(InternalServerErrorException.class).verify();

        verify(dynamoDb, times(1)).describeTable(any(DescribeTableRequest.class));
    }

    @Test
    void shouldAcceptAMigrationRecordedConcurrentlyWithTheSameChecksum() {
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(alreadyExists());
        tablesAreActive();
        history();
        timeToLive(TimeToLiveStatus.ENABLED, TTL_ATTRIBUTE);
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(ConditionalCheckFailedException.builder().build()));
        Map<String, String> checksums = new HashMap<>();
        MIGRATIONS.forEach(migration -> checksums.put(migration.id(), migration.checksum()));
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenAnswer(invocation -> {
            GetItemRequest request = invocation.getArgument(0);
            String id = request.key().get("migrationId").s();
            return done(GetItemResponse.builder().item(record(id, checksums.get(id))));
        });

        StepVerifier.create(runner.migrate()).expectNext(IDS).verifyComplete();

        ArgumentCaptor<GetItemRequest> reads = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamoDb, times(5)).getItem(reads.capture());
        assertThat(reads.getAllValues()).allSatisfy(read -> {
            assertThat(read.tableName()).isEqualTo("p-migrations");
            assertThat(read.consistentRead()).isTrue();
        });
    }

    @Test
    void shouldRejectAMigrationRecordedConcurrentlyWithAnotherChecksum() {
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(alreadyExists());
        tablesAreActive();
        history();
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(ConditionalCheckFailedException.builder().build()));
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(done(GetItemResponse.builder().item(Map.of("migrationId", AttributeValue.fromS("x")))));

        StepVerifier.create(runner.migrate())
                .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("Checksum mismatch"))
                .verify();
    }

    @Test
    void shouldReadTheHistoryAcrossPages() {
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(alreadyExists());
        tablesAreActive();
        List<Map<String, AttributeValue>> records = MIGRATIONS.stream()
                .map(migration -> record(migration.id(), migration.checksum()))
                .toList();
        Map<String, AttributeValue> lastKey = Map.of("migrationId", AttributeValue.fromS(IDS.get(1)));
        when(dynamoDb.scan(any(ScanRequest.class)))
                .thenReturn(done(ScanResponse.builder().items(records.subList(0, 2)).lastEvaluatedKey(lastKey)))
                .thenReturn(done(ScanResponse.builder().items(records.subList(2, 5)).lastEvaluatedKey(Map.of())));

        StepVerifier.create(runner.migrate()).expectNext(List.of()).verifyComplete();

        ArgumentCaptor<ScanRequest> scans = ArgumentCaptor.forClass(ScanRequest.class);
        verify(dynamoDb, times(2)).scan(scans.capture());
        assertThat(scans.getAllValues().get(1).exclusiveStartKey()).isEqualTo(lastKey);
    }

    @Test
    void shouldPropagateUnexpectedDynamoDbFailures() {
        when(dynamoDb.createTable(any(CreateTableRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(InternalServerErrorException.builder().build()));

        StepVerifier.create(runner.migrate()).expectError(InternalServerErrorException.class).verify();
    }

    @Test
    void shouldBlockOnceAtStartup() {
        when(dynamoDb.createTable(any(CreateTableRequest.class))).thenReturn(alreadyExists());
        tablesAreActive();
        history(MIGRATIONS.stream().map(migration -> record(migration.id(), migration.checksum())).toList());

        runner.afterSingletonsInstantiated();

        verify(dynamoDb).scan(any(ScanRequest.class));
    }

    @Test
    void shouldAbortTheStartupOnFailure() {
        when(dynamoDb.createTable(any(CreateTableRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(InternalServerErrorException.builder().build()));

        assertThatThrownBy(runner::afterSingletonsInstantiated).isInstanceOf(InternalServerErrorException.class);
    }

    @Test
    void shouldUseProductionPollingDefaults() {
        DynamoMigrationRunner defaults = new DynamoMigrationRunner(dynamoDb, READER,
                TableDefinitionParser.forTables(TABLES), "p-migrations", CLOCK);

        assertThat(defaults).isNotNull();
        assertThatThrownBy(() -> new DynamoMigrationRunner(null, READER, TableDefinitionParser.forTables(TABLES),
                "p-migrations", CLOCK)).isInstanceOf(NullPointerException.class);
    }

    private void tablesAreActive() {
        when(dynamoDb.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(described(TableStatus.ACTIVE, IndexStatus.ACTIVE));
    }

    private void history() {
        history(List.of());
    }

    private static boolean recordOf(PutItemRequest put, String migrationId) {
        return put != null && migrationId.equals(put.item().get("migrationId").s());
    }

    private void timeToLive(TimeToLiveStatus status, String attribute) {
        when(dynamoDb.describeTimeToLive(any(DescribeTimeToLiveRequest.class)))
                .thenReturn(timeToLiveDescribed(status, attribute));
    }

    private static CompletableFuture<DescribeTimeToLiveResponse> timeToLiveDescribed(TimeToLiveStatus status,
                                                                         String attribute) {
        return done(DescribeTimeToLiveResponse.builder().timeToLiveDescription(TimeToLiveDescription.builder()
                .timeToLiveStatus(status)
                .attributeName(attribute)
                .build()));
    }

    private static DynamoDbException validationError(String message) {
        return (DynamoDbException) DynamoDbException.builder().statusCode(400).message(message).build();
    }

    private void history(List<Map<String, AttributeValue>> records) {
        when(dynamoDb.scan(any(ScanRequest.class))).thenReturn(done(ScanResponse.builder().items(records)));
    }

    private static Map<String, AttributeValue> record(String id, String checksum) {
        return Map.of("migrationId", AttributeValue.fromS(id), "checksum", AttributeValue.fromS(checksum),
                "appliedAt", AttributeValue.fromN("1"));
    }

    private static CompletableFuture<CreateTableResponse> alreadyExists() {
        return CompletableFuture.failedFuture(ResourceInUseException.builder().message("exists").build());
    }

    private static CompletableFuture<DescribeTableResponse> described(TableStatus status, IndexStatus... indexes) {
        TableDescription.Builder table = TableDescription.builder().tableStatus(status);
        if (indexes.length > 0) {
            table.globalSecondaryIndexes(Stream.of(indexes)
                    .map(index -> GlobalSecondaryIndexDescription.builder().indexStatus(index).build())
                    .toList());
        }
        return done(DescribeTableResponse.builder().table(table.build()));
    }

    private static <T> CompletableFuture<T> done(SdkBuilder<?, T> builder) {
        return CompletableFuture.completedFuture(builder.build());
    }
}
