package com.ticketing.persistence.impl;

import static com.ticketing.persistence.impl.support.DynamoAttributes.epochMillis;
import static com.ticketing.persistence.impl.support.DynamoAttributes.number;
import static com.ticketing.persistence.impl.support.DynamoAttributes.string;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.PageData;
import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.persistence.EventPersistence;
import com.ticketing.persistence.entity.EventEntity;
import com.ticketing.persistence.mapper.EventEntityMapper;
import com.ticketing.persistence.impl.support.CursorCodec;
import com.ticketing.persistence.impl.support.DynamoDbTableNames;
import com.ticketing.persistence.impl.support.EntitySchemas;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

@Repository
public class EventPersistenceImpl implements EventPersistence {
    private static final Map<String, String> STATUS_NAME = Map.of("#status", "status");

    private static final Map<String, ScalarAttributeType> STATUS_INDEX_KEY = Map.of(
            "eventId", ScalarAttributeType.S,
            "status", ScalarAttributeType.S,
            "eventDate", ScalarAttributeType.N);

    private final DynamoDbAsyncClient dynamoDb;
    private final DynamoDbAsyncTable<EventEntity> table;
    private final EventEntityMapper mapper;
    private final String tableName;

    public EventPersistenceImpl(DynamoDbAsyncClient dynamoDb, DynamoDbEnhancedAsyncClient enhancedClient,
                                DynamoDbTableNames tableNames, EventEntityMapper mapper) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableNames.events();
        this.table = enhancedClient.table(tableName, EntitySchemas.EVENT);
        this.mapper = mapper;
    }

    @Override
    public Mono<EventData> create(EventData event) {
        return Mono.defer(() -> {
            Map<String, AttributeValue> item = mapper.toItem(event);
            return Mono.fromFuture(() -> dynamoDb.putItem(PutItemRequest.builder()
                            .tableName(tableName)
                            .item(item)
                            .conditionExpression("attribute_not_exists(eventId)")
                            .build()))
                    .then(Mono.fromCallable(() -> mapper.fromItem(item)));
        });
    }

    @Override
    public Mono<EventData> findById(String eventId) {
        return Mono.fromFuture(() -> table.getItem(GetItemEnhancedRequest.builder()
                        .key(Key.builder().partitionValue(eventId).build())
                        .consistentRead(true)
                        .build()))
                .map(mapper::toDomain);
    }

    @Override
    public Mono<PageData<EventData>> findByStatus(EventStatusEnum status, int limit, String cursor) {
        return Mono.defer(() -> {
            if (limit < 1) {
                return Mono.error(new IllegalArgumentException("limit must be >= 1 but was " + limit));
            }
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(tableName)
                    .indexName(EventEntity.STATUS_EVENT_DATE_INDEX)
                    .keyConditionExpression("#status = :status")
                    .expressionAttributeNames(STATUS_NAME)
                    .expressionAttributeValues(Map.of(":status", string(status.name())))
                    .limit(limit);
            Map<String, AttributeValue> startKey = startKey(status, cursor);
            if (!startKey.isEmpty()) {
                request.exclusiveStartKey(startKey);
            }
            return Mono.fromFuture(dynamoDb.query(request.build()));
        }).map(response -> new PageData<>(
                response.items().stream().map(mapper::fromItem).toList(),
                CursorCodec.encode(response.lastEvaluatedKey())));
    }

    @Override
    public Mono<Boolean> updateStatus(String eventId, EventStatusEnum expected, EventStatusEnum target, Instant now) {
        return Mono.fromFuture(() -> dynamoDb.updateItem(UpdateItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("eventId", string(eventId)))
                        .updateExpression("SET #status = :target, updatedAt = :now ADD version :one")
                        .conditionExpression("#status = :expected")
                        .expressionAttributeNames(STATUS_NAME)
                        .expressionAttributeValues(Map.of(
                                ":target", string(target.name()),
                                ":expected", string(expected.name()),
                                ":now", epochMillis(now),
                                ":one", number(1)))
                        .build()))
                .thenReturn(true)
                .onErrorReturn(ConditionalCheckFailedException.class, false);
    }

    private static Map<String, AttributeValue> startKey(EventStatusEnum status, String cursor) {
        Map<String, AttributeValue> startKey = CursorCodec.decode(cursor, STATUS_INDEX_KEY);
        if (!startKey.isEmpty() && !status.name().equals(startKey.get("status").s())) {
            throw new DomainValidationException("Pagination cursor does not belong to status " + status);
        }
        return startKey;
    }
}
