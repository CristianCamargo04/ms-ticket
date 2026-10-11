package com.ticketing.persistence.impl;

import static com.ticketing.persistence.impl.support.DynamoAttributes.string;

import com.ticketing.domain.data.TicketData;
import com.ticketing.domain.event.EventPolicy;
import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.persistence.TicketPersistence;
import com.ticketing.persistence.entity.TicketEntity;
import com.ticketing.persistence.impl.mapper.TicketEntityMapper;
import com.ticketing.persistence.impl.support.BatchRetryPolicy;
import com.ticketing.persistence.impl.support.DynamoDbTableNames;
import com.ticketing.persistence.impl.support.QueryPaginator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

@Repository
public class TicketPersistenceImpl implements TicketPersistence {
    static final int BATCH_WRITE_SIZE = 25;

    static final int BATCH_GET_SIZE = 100;

    private static final String AVAILABLE_RANGE_START = TicketStatusEnum.AVAILABLE.name() + "#";

    private static final String AVAILABLE_RANGE_END = TicketStatusEnum.AVAILABLE.name() + "$";

    private final DynamoDbAsyncClient dynamoDb;
    private final String tableName;
    private final TicketEntityMapper mapper;
    private final int writeConcurrency;
    private final BatchRetryPolicy retryPolicy = BatchRetryPolicy.DEFAULT;

    public TicketPersistenceImpl(DynamoDbAsyncClient dynamoDb, DynamoDbTableNames tableNames,
                                 TicketEntityMapper mapper, EventPolicy eventPolicy) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableNames.tickets();
        this.mapper = mapper;
        this.writeConcurrency = eventPolicy.ticketWriteConcurrency();
    }

    @Override
    public Mono<Long> saveAll(Flux<TicketData> tickets) {
        return tickets
                .map(ticket -> WriteRequest.builder()
                        .putRequest(PutRequest.builder().item(mapper.toItem(ticket)).build())
                        .build())
                .buffer(BATCH_WRITE_SIZE)
                .flatMap(batch -> writeBatch(Map.of(tableName, batch), 1).thenReturn((long) batch.size()),
                        writeConcurrency)
                .reduce(0L, Long::sum);
    }

    @Override
    public Flux<TicketData> findAvailable(String eventId, String fromTicketId, int limit) {
        return Flux.defer(() -> {
            if (limit < 1) {
                return Flux.empty();
            }
            QueryRequest request = QueryRequest.builder()
                    .tableName(tableName)
                    .indexName(TicketEntity.EVENT_STATUS_KEY_INDEX)
                    .keyConditionExpression("eventId = :eventId AND statusKey BETWEEN :from AND :to")
                    .expressionAttributeValues(Map.of(
                            ":eventId", string(eventId),
                            ":from", string(AVAILABLE_RANGE_START + Objects.requireNonNullElse(fromTicketId, "")),
                            ":to", string(AVAILABLE_RANGE_END)))
                    .limit(limit)
                    .build();
            return QueryPaginator.items(dynamoDb, request).take(limit).map(mapper::fromItem);
        });
    }

    @Override
    public Flux<TicketData> findByIds(String eventId, List<String> ticketIds) {
        return Flux.defer(() -> {
            List<String> distinctIds = ticketIds.stream().distinct().toList();
            return Flux.fromIterable(distinctIds)
                    .buffer(BATCH_GET_SIZE)
                    .concatMap(chunk -> readBatch(Map.of(tableName, KeysAndAttributes.builder()
                            .keys(chunk.stream().map(ticketId -> key(eventId, ticketId)).toList())
                            .consistentRead(true)
                            .build()), 1))
                    .map(mapper::fromItem)
                    .collectMap(TicketData::ticketId)
                    .flatMapIterable(found -> distinctIds.stream().filter(found::containsKey).map(found::get).toList());
        });
    }

    private Mono<Void> writeBatch(Map<String, List<WriteRequest>> requestItems, int attempt) {
        return Mono.fromFuture(() -> dynamoDb.batchWriteItem(BatchWriteItemRequest.builder()
                        .requestItems(requestItems)
                        .build()))
                .flatMap(response -> {
                    if (!response.hasUnprocessedItems() || response.unprocessedItems().isEmpty()) {
                        return Mono.empty();
                    }
                    if (!retryPolicy.canRetry(attempt)) {
                        return Mono.error(new InventoryConflictException(
                                "Ticket batch write still has unprocessed items after " + attempt + " attempts"));
                    }
                    return Mono.delay(retryPolicy.delayAfter(attempt))
                            .then(writeBatch(response.unprocessedItems(), attempt + 1));
                });
    }

    private Flux<Map<String, AttributeValue>> readBatch(Map<String, KeysAndAttributes> requestItems, int attempt) {
        return Mono.fromFuture(() -> dynamoDb.batchGetItem(BatchGetItemRequest.builder()
                        .requestItems(requestItems)
                        .build()))
                .flatMapMany(response -> {
                    Flux<Map<String, AttributeValue>> items =
                            Flux.fromIterable(response.responses().getOrDefault(tableName, List.of()));
                    if (!response.hasUnprocessedKeys() || response.unprocessedKeys().isEmpty()) {
                        return items;
                    }
                    if (!retryPolicy.canRetry(attempt)) {
                        return Flux.error(new InventoryConflictException(
                                "Ticket batch read still has unprocessed keys after " + attempt + " attempts"));
                    }
                    return items.concatWith(Mono.delay(retryPolicy.delayAfter(attempt))
                            .thenMany(readBatch(response.unprocessedKeys(), attempt + 1)));
                });
    }

    private static Map<String, AttributeValue> key(String eventId, String ticketId) {
        return Map.of("eventId", string(eventId), "ticketId", string(ticketId));
    }
}
