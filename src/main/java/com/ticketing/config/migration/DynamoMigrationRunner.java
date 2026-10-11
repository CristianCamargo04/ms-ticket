package com.ticketing.config.migration;

import com.ticketing.config.migration.MigrationChangelogReader.Migration;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.IndexStatus;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
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

@Slf4j
public class DynamoMigrationRunner implements SmartInitializingSingleton {
    static final String MIGRATION_ID = "migrationId";
    static final String CHECKSUM = "checksum";
    static final String APPLIED_AT = "appliedAt";

    private static final Set<TimeToLiveStatus> ACTIVE_TIME_TO_LIVE =
            EnumSet.of(TimeToLiveStatus.ENABLED, TimeToLiveStatus.ENABLING);

    private final DynamoDbAsyncClient dynamoDb;
    private final MigrationChangelogReader changelogReader;
    private final TableDefinitionParser tableDefinitionParser;
    private final String historyTable;
    private final Clock clock;
    private final Duration pollInterval;
    private final int maxPolls;
    private final Duration startupTimeout;

    public DynamoMigrationRunner(DynamoDbAsyncClient dynamoDb, MigrationChangelogReader changelogReader,
                                 TableDefinitionParser tableDefinitionParser, String historyTable, Clock clock) {
        this(dynamoDb, changelogReader, tableDefinitionParser, historyTable, clock, Duration.ofMillis(500), 240,
                Duration.ofMinutes(5));
    }

    DynamoMigrationRunner(DynamoDbAsyncClient dynamoDb, MigrationChangelogReader changelogReader,
                          TableDefinitionParser tableDefinitionParser, String historyTable, Clock clock,
                          Duration pollInterval, int maxPolls, Duration startupTimeout) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        this.changelogReader = Objects.requireNonNull(changelogReader, "changelogReader");
        this.tableDefinitionParser = Objects.requireNonNull(tableDefinitionParser, "tableDefinitionParser");
        this.historyTable = Objects.requireNonNull(historyTable, "historyTable");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.maxPolls = maxPolls;
        this.startupTimeout = Objects.requireNonNull(startupTimeout, "startupTimeout");
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> applied = Objects.requireNonNullElse(migrate().block(startupTimeout), List.of());
        log.info("DynamoDB schema is up to date ({} migration(s) applied by this run)", applied.size());
    }

    public Mono<List<String>> migrate() {
        return Mono.fromCallable(changelogReader::read)
                .flatMap(migrations -> ensureHistoryTable()
                        .then(appliedChecksums())
                        .flatMap(applied -> verifyChecksums(migrations, applied)
                                .thenMany(Flux.fromIterable(migrations)
                                        .filter(migration -> !applied.containsKey(migration.id()))
                                        .concatMap(this::apply))
                                .collectList()));
    }

    private Mono<Void> ensureHistoryTable() {
        return createTable(CreateTableRequest.builder()
                .tableName(historyTable)
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName(MIGRATION_ID)
                        .attributeType(ScalarAttributeType.S)
                        .build())
                .keySchema(KeySchemaElement.builder().attributeName(MIGRATION_ID).keyType(KeyType.HASH).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build())
                .then(waitUntilActive(historyTable));
    }

    private Mono<Map<String, String>> appliedChecksums() {
        return scan(ScanRequest.builder().tableName(historyTable).consistentRead(true).build())
                .collectMap(item -> item.get(MIGRATION_ID).s(),
                        item -> Optional.ofNullable(item.get(CHECKSUM)).map(AttributeValue::s).orElse(""));
    }

    private Flux<Map<String, AttributeValue>> scan(ScanRequest request) {
        return Mono.fromFuture(() -> dynamoDb.scan(request))
                .flatMapMany(response -> hasNextPage(response)
                        ? Flux.fromIterable(response.items()).concatWith(Flux.defer(() -> scan(
                                request.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build())))
                        : Flux.fromIterable(response.items()));
    }

    private static boolean hasNextPage(ScanResponse response) {
        return response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty();
    }

    private static Mono<Void> verifyChecksums(List<Migration> migrations, Map<String, String> applied) {
        return Mono.defer(() -> {
            for (Migration migration : migrations) {
                String recorded = applied.get(migration.id());
                if (recorded != null && !recorded.equals(migration.checksum())) {
                    return Mono.error(checksumMismatch(migration, recorded));
                }
            }
            return Mono.empty();
        });
    }

    private Mono<String> apply(Migration migration) {
        return Mono.fromCallable(() -> tableDefinitionParser.parse(migration.definition()))
                .flatMap(definition -> {
                    String tableName = definition.createTable().tableName();
                    return createTable(definition.createTable())
                            .then(waitUntilActive(tableName))
                            .then(ensureTimeToLive(tableName, definition.timeToLive()));
                })
                .then(record(migration))
                .thenReturn(migration.id())
                .doOnNext(id -> log.info("Applied DynamoDB migration {} ({})", id, migration.file()));
    }

    private Mono<Void> createTable(CreateTableRequest request) {
        return Mono.fromFuture(() -> dynamoDb.createTable(request))
                .then()
                .onErrorResume(ResourceInUseException.class, exists -> {
                    log.info("DynamoDB table {} already exists, creation skipped", request.tableName());
                    return Mono.empty();
                });
    }

    private Mono<Void> waitUntilActive(String tableName) {
        return Mono.defer(() -> Mono.fromFuture(dynamoDb.describeTable(DescribeTableRequest.builder()
                        .tableName(tableName)
                        .build())))
                .flatMap(response -> isActive(response.table())
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(new TableNotActiveException(tableName)))
                .retryWhen(Retry.fixedDelay(maxPolls, pollInterval)
                        .filter(error -> error instanceof TableNotActiveException
                                || error instanceof ResourceNotFoundException)
                        .onRetryExhaustedThrow((spec, signal) -> new IllegalStateException(
                                "DynamoDB table " + tableName + " did not become ACTIVE", signal.failure())));
    }

    private static boolean isActive(TableDescription table) {
        return table.tableStatus() == TableStatus.ACTIVE
                && table.globalSecondaryIndexes().stream().allMatch(index -> index.indexStatus() == IndexStatus.ACTIVE);
    }

    private Mono<Void> ensureTimeToLive(String tableName, TimeToLiveSpecification wanted) {
        if (wanted == null) {
            return Mono.empty();
        }
        return describeTimeToLive(tableName).flatMap(current -> switch (stateOf(current, wanted)) {
            case CONVERGED -> Mono.<Void>empty();
            case CONFLICT -> Mono.<Void>error(timeToLiveConflict(tableName, current, wanted));
            case PENDING -> updateTimeToLive(tableName, wanted)
                    .onErrorResume(DynamoDbException.class, failure -> describeTimeToLive(tableName)
                            .flatMap(after -> stateOf(after, wanted) == TimeToLiveState.CONVERGED
                                    ? Mono.<Void>empty()
                                    : Mono.<Void>error(failure)));
        });
    }

    private Mono<TimeToLiveDescription> describeTimeToLive(String tableName) {
        return Mono.fromFuture(() -> dynamoDb.describeTimeToLive(DescribeTimeToLiveRequest.builder()
                        .tableName(tableName)
                        .build()))
                .map(response -> Optional.ofNullable(response.timeToLiveDescription())
                        .orElseGet(() -> TimeToLiveDescription.builder()
                                .timeToLiveStatus(TimeToLiveStatus.DISABLED)
                                .build()));
    }

    private Mono<Void> updateTimeToLive(String tableName, TimeToLiveSpecification wanted) {
        return Mono.fromFuture(() -> dynamoDb.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                        .tableName(tableName)
                        .timeToLiveSpecification(wanted)
                        .build()))
                .doOnNext(updated -> log.info("Time to live of DynamoDB table {} set to {} on {}", tableName,
                        Boolean.TRUE.equals(wanted.enabled()) ? "enabled" : "disabled", wanted.attributeName()))
                .then();
    }

    private static TimeToLiveState stateOf(TimeToLiveDescription current, TimeToLiveSpecification wanted) {
        boolean active = ACTIVE_TIME_TO_LIVE.contains(current.timeToLiveStatus());
        if (!Boolean.TRUE.equals(wanted.enabled())) {
            return active ? TimeToLiveState.PENDING : TimeToLiveState.CONVERGED;
        }
        if (!active) {
            return TimeToLiveState.PENDING;
        }
        return wanted.attributeName().equals(current.attributeName())
                ? TimeToLiveState.CONVERGED
                : TimeToLiveState.CONFLICT;
    }

    private static IllegalStateException timeToLiveConflict(String tableName, TimeToLiveDescription current,
                                                            TimeToLiveSpecification wanted) {
        return new IllegalStateException("DynamoDB table " + tableName + " has its time to live enabled on attribute "
                + current.attributeName() + " but the migration declares " + wanted.attributeName()
                + "; only one TTL attribute is allowed per table, fix the table or add a new migration.");
    }

    private Mono<Void> record(Migration migration) {
        return Mono.fromFuture(() -> dynamoDb.putItem(PutItemRequest.builder()
                        .tableName(historyTable)
                        .item(Map.of(
                                MIGRATION_ID, AttributeValue.fromS(migration.id()),
                                CHECKSUM, AttributeValue.fromS(migration.checksum()),
                                APPLIED_AT, AttributeValue.fromN(Long.toString(clock.millis()))))
                        .conditionExpression("attribute_not_exists(" + MIGRATION_ID + ")")
                        .build()))
                .then()
                .onErrorResume(ConditionalCheckFailedException.class, recordedConcurrently ->
                        verifyRecordedChecksum(migration));
    }

    private Mono<Void> verifyRecordedChecksum(Migration migration) {
        return Mono.fromFuture(() -> dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(historyTable)
                        .key(Map.of(MIGRATION_ID, AttributeValue.fromS(migration.id())))
                        .consistentRead(true)
                        .build()))
                .flatMap(response -> {
                    String recorded = Optional.ofNullable(response.item().get(CHECKSUM))
                            .map(AttributeValue::s)
                            .orElse("");
                    return recorded.equals(migration.checksum())
                            ? Mono.<Void>empty()
                            : Mono.<Void>error(checksumMismatch(migration, recorded));
                });
    }

    private static IllegalStateException checksumMismatch(Migration migration, String recorded) {
        return new IllegalStateException("Checksum mismatch for applied DynamoDB migration " + migration.id()
                + " (" + migration.file() + "): recorded " + recorded + " but the file hashes to "
                + migration.checksum() + ". Applied migrations must never change; add a new migration instead.");
    }

    private enum TimeToLiveState {
        CONVERGED,

        PENDING,

        CONFLICT
    }

    private static final class TableNotActiveException extends RuntimeException {
        TableNotActiveException(String tableName) {
            super("DynamoDB table " + tableName + " is not ACTIVE yet", null, false, false);
        }
    }
}
