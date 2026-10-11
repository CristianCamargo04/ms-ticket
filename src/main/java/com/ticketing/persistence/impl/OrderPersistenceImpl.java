package com.ticketing.persistence.impl;

import static com.ticketing.persistence.impl.support.DynamoAttributes.epochMillis;
import static com.ticketing.persistence.impl.support.DynamoAttributes.number;
import static com.ticketing.persistence.impl.support.DynamoAttributes.string;

import com.ticketing.domain.data.OrderData;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.persistence.OrderPersistence;
import com.ticketing.persistence.entity.OrderEntity;
import com.ticketing.persistence.impl.mapper.OrderEntityMapper;
import com.ticketing.persistence.impl.support.DeadlinePartitioner;
import com.ticketing.persistence.impl.support.DynamoDbTableNames;
import com.ticketing.persistence.impl.support.EntitySchemas;
import com.ticketing.persistence.impl.support.QueryPaginator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
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
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * DynamoDB adapter of the {@code orders} table, including the sparse, sharded {@code deadline-index} scanned by the
 * sweepers (implementation spec 6.2).
 *
 * <p>Every status change is a conditional write on the expected processing status, so concurrent workers, sweepers
 * and API calls never overwrite each other: the loser gets {@code false} and re-reads.
 */
@Repository
public class OrderPersistenceImpl implements OrderPersistence {

    private static final Comparator<OrderData> BY_DEADLINE = Comparator.comparing(OrderData::deadlineAt)
            .thenComparing(OrderData::orderId);

    private final DynamoDbAsyncClient dynamoDb;
    private final DynamoDbAsyncTable<OrderEntity> table;
    private final OrderEntityMapper mapper;
    private final DeadlinePartitioner partitioner;
    private final String tableName;

    /**
     * Creates the adapter.
     *
     * @param dynamoDb       low-level client
     * @param enhancedClient enhanced client
     * @param tableNames     physical table names
     * @param mapper         order mapper
     * @param partitioner    deadline index partitioner
     */
    public OrderPersistenceImpl(DynamoDbAsyncClient dynamoDb, DynamoDbEnhancedAsyncClient enhancedClient,
                                DynamoDbTableNames tableNames, OrderEntityMapper mapper,
                                DeadlinePartitioner partitioner) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableNames.orders();
        this.table = enhancedClient.table(tableName, EntitySchemas.ORDER);
        this.mapper = mapper;
        this.partitioner = partitioner;
    }

    @Override
    public Mono<Boolean> insertIfAbsent(OrderData order) {
        return Mono.fromFuture(() -> dynamoDb.putItem(PutItemRequest.builder()
                        .tableName(tableName)
                        .item(mapper.toItem(order))
                        .conditionExpression("attribute_not_exists(orderId)")
                        .build()))
                .thenReturn(true)
                .onErrorReturn(ConditionalCheckFailedException.class, false);
    }

    @Override
    public Mono<OrderData> findById(String orderId) {
        return Mono.fromFuture(() -> table.getItem(GetItemEnhancedRequest.builder()
                        .key(Key.builder().partitionValue(orderId).build())
                        .consistentRead(true)
                        .build()))
                .map(mapper::toDomain);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every shard is queried in parallel (at most {@code limit} orders each); the merged result is sorted by
     * deadline so that the most overdue orders are processed first, whatever their shard.
     */
    @Override
    public Flux<OrderData> findDueOrders(DeadlineTypeEnum type, Instant now, int limit) {
        return Flux.defer(() -> {
            if (limit < 1) {
                return Flux.empty();
            }
            return Flux.fromIterable(partitioner.partitionsOf(type))
                    .flatMap(partition -> dueOrdersOf(partition, now, limit))
                    .sort(BY_DEADLINE)
                    .take(limit);
        });
    }

    @Override
    public Mono<Boolean> transitionProcessingStatus(String orderId, OrderProcessingStatusEnum expected,
                                                    OrderProcessingStatusEnum target, String reason, Instant now) {
        return Mono.defer(() -> {
            Map<String, AttributeValue> values = new HashMap<>();
            values.put(":target", string(target.name()));
            values.put(":expected", string(expected.name()));
            values.put(":now", epochMillis(now));
            values.put(":one", number(1));
            StringBuilder update = new StringBuilder("SET processingStatus = :target, updatedAt = :now");
            List<String> removed = new ArrayList<>();
            if (reason == null) {
                removed.add("reason");
            } else {
                update.append(", reason = :reason");
                values.put(":reason", string(reason));
            }
            if (target.isTerminal()) {
                removed.add("deadlinePartition");
                removed.add("deadlineAt");
            }
            if (!removed.isEmpty()) {
                update.append(" REMOVE ").append(String.join(", ", removed));
            }
            update.append(" ADD version :one");
            return Mono.fromFuture(dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(key(orderId))
                    .updateExpression(update.toString())
                    .conditionExpression("processingStatus = :expected")
                    .expressionAttributeValues(values)
                    .build()));
        }).thenReturn(true).onErrorReturn(ConditionalCheckFailedException.class, false);
    }

    @Override
    public Mono<Boolean> rescheduleDeadline(String orderId, OrderProcessingStatusEnum expected, Instant deadlineAt,
                                            Instant now) {
        return Mono.fromFuture(() -> dynamoDb.updateItem(UpdateItemRequest.builder()
                        .tableName(tableName)
                        .key(key(orderId))
                        .updateExpression("SET deadlineAt = :deadlineAt, updatedAt = :now ADD version :one")
                        .conditionExpression("processingStatus = :expected")
                        .expressionAttributeValues(Map.of(
                                ":deadlineAt", epochMillis(deadlineAt),
                                ":expected", string(expected.name()),
                                ":now", epochMillis(now),
                                ":one", number(1)))
                        .build()))
                .thenReturn(true)
                .onErrorReturn(ConditionalCheckFailedException.class, false);
    }

    private Flux<OrderData> dueOrdersOf(String partition, Instant now, int limit) {
        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .indexName(OrderEntity.DEADLINE_INDEX)
                .keyConditionExpression("deadlinePartition = :partition AND deadlineAt <= :now")
                .expressionAttributeValues(Map.of(":partition", string(partition), ":now", epochMillis(now)))
                .limit(limit)
                .build();
        return QueryPaginator.items(dynamoDb, request).take(limit).map(mapper::fromItem);
    }

    private static Map<String, AttributeValue> key(String orderId) {
        return Map.of("orderId", string(orderId));
    }
}
