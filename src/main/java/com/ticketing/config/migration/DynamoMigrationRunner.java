package com.ticketing.config.migration;

import com.ticketing.config.migration.MigrationChangelogReader.Migration;
import com.ticketing.config.migration.TableDefinitionParser.TableDefinition;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

/**
 * Applies the pending DynamoDB migrations at startup (local and ci profiles only; in AWS, Terraform owns the schema).
 * Implementation spec 6.4.
 *
 * <p>Algorithm, idempotent and safe to run from several instances at once:
 * <ol>
 *     <li>create the {@code schema-migrations} history table if needed (the runner owns it, it is not a
 *     migration);</li>
 *     <li>read the applied migrations and fail fast if the checksum of any of them differs from its file (an
 *     applied migration must never be edited: add a new one);</li>
 *     <li>for every pending migration, in changelog order: create the table (an existing table is skipped), wait
 *     until the table and its indexes are {@code ACTIVE}, enable the time to live when the file declares one, and
 *     record the migration with its checksum (a conditional put: a concurrent runner that recorded it first must
 *     have recorded the same checksum).</li>
 * </ol>
 * The algorithm is reactive ({@link #migrate()}); {@link #afterSingletonsInstantiated()} blocks on it once, before
 * the HTTP server and the queue listeners start, which is the only blocking call allowed in the application
 * (configuration code at startup).
 */
public class DynamoMigrationRunner implements SmartInitializingSingleton {

    static final String MIGRATION_ID = "migrationId";
    static final String CHECKSUM = "checksum";
    static final String APPLIED_AT = "appliedAt";

    private static final Logger log = LoggerFactory.getLogger(DynamoMigrationRunner.class);

    private final DynamoDbAsyncClient dynamoDb;
    private final MigrationChangelogReader changelogReader;
    private final TableDefinitionParser tableDefinitionParser;
    private final String historyTable;
    private final Clock clock;
    private final Duration pollInterval;
    private final int maxPolls;
    private final Duration startupTimeout;

    /**
     * Creates a runner polling table status every 500 ms (at most 2 minutes per table) and giving up after 5 minutes.
     *
     * @param dynamoDb              low-level client
     * @param changelogReader       reads the migrations
     * @param tableDefinitionParser converts migration files into requests
     * @param historyTable          physical name of the {@code schema-migrations} table
     * @param clock                 source of {@code appliedAt}
     */
    public DynamoMigrationRunner(DynamoDbAsyncClient dynamoDb, MigrationChangelogReader changelogReader,
                                 TableDefinitionParser tableDefinitionParser, String historyTable, Clock clock) {
        this(dynamoDb, changelogReader, tableDefinitionParser, historyTable, clock, Duration.ofMillis(500), 240,
                Duration.ofMinutes(5));
    }

    /**
     * Creates a runner with explicit polling settings.
     *
     * @param dynamoDb              low-level client
     * @param changelogReader       reads the migrations
     * @param tableDefinitionParser converts migration files into requests
     * @param historyTable          physical name of the {@code schema-migrations} table
     * @param clock                 source of {@code appliedAt}
     * @param pollInterval          delay between two table status checks
     * @param maxPolls              status checks before giving up on a table
     * @param startupTimeout        maximum duration of the whole run at startup
     */
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

    /**
     * Applies the pending migrations once every singleton exists and before the lifecycle beans (web server, queue
     * listeners, sweepers) start. A failure aborts the application startup.
     */
    @Override
    public void afterSingletonsInstantiated() {
        List<String> applied = Objects.requireNonNullElse(migrate().block(startupTimeout), List.of());
        log.info("DynamoDB schema is up to date ({} migration(s) applied by this run)", applied.size());
    }

    /**
     * Applies the pending migrations.
     *
     * @return the ids of the migrations applied by this run, in order; error signal on an invalid changelog, a
     *         checksum mismatch or a DynamoDB failure
     */
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
                .flatMap(definition -> createTable(definition.createTable())
                        .flatMap(created -> waitUntilActive(definition.createTable().tableName())
                                .then(created ? enableTimeToLive(definition) : Mono.empty())))
                .then(record(migration))
                .thenReturn(migration.id())
                .doOnNext(id -> log.info("Applied DynamoDB migration {} ({})", id, migration.file()));
    }

    /**
     * Creates a table.
     *
     * @return {@code true} when created, {@code false} when it already existed (or is being created by another
     *         runner)
     */
    private Mono<Boolean> createTable(CreateTableRequest request) {
        return Mono.fromFuture(() -> dynamoDb.createTable(request))
                .thenReturn(true)
                .onErrorResume(ResourceInUseException.class, exists -> {
                    log.info("DynamoDB table {} already exists, creation skipped", request.tableName());
                    return Mono.just(false);
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

    private Mono<Void> enableTimeToLive(TableDefinition definition) {
        TimeToLiveSpecification timeToLive = definition.timeToLive();
        if (timeToLive == null) {
            return Mono.empty();
        }
        return Mono.fromFuture(() -> dynamoDb.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                        .tableName(definition.createTable().tableName())
                        .timeToLiveSpecification(timeToLive)
                        .build()))
                .then();
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

    /** Signals that a table (or one of its indexes) is still being created; retried by the status polling. */
    private static final class TableNotActiveException extends RuntimeException {

        TableNotActiveException(String tableName) {
            super("DynamoDB table " + tableName + " is not ACTIVE yet", null, false, false);
        }
    }
}
