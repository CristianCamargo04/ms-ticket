package com.ticketing.persistence.impl;

import static com.ticketing.persistence.impl.support.DynamoAttributes.epochMillis;
import static com.ticketing.persistence.impl.support.DynamoAttributes.string;

import com.ticketing.persistence.DistributedLockPersistence;
import com.ticketing.persistence.entity.DistributedLockEntity;
import com.ticketing.persistence.impl.support.DynamoDbTableNames;
import com.ticketing.persistence.impl.support.EntitySchemas;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Lease-based distributed lock on the {@code distributed-locks} table.
 *
 * <p>Acquisition is a single conditional put, atomic on the server: it succeeds when the lock does not exist, when
 * its lease is over or when the caller already owns it (renewal). {@code expiresAt} (epoch seconds) lets the DynamoDB
 * TTL garbage-collect leases whose owner crashed; correctness never depends on the TTL because the condition
 * compares {@code leaseUntil} with the caller's clock.
 */
@Repository
public class DistributedLockPersistenceImpl implements DistributedLockPersistence {

    private static final Map<String, String> OWNER_NAME = Map.of("#owner", "owner");

    private final DynamoDbAsyncClient dynamoDb;
    private final String tableName;

    /**
     * Creates the adapter.
     *
     * @param dynamoDb   low-level client
     * @param tableNames physical table names
     */
    public DistributedLockPersistenceImpl(DynamoDbAsyncClient dynamoDb, DynamoDbTableNames tableNames) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableNames.distributedLocks();
    }

    @Override
    public Mono<Boolean> tryAcquire(String lockName, String owner, Duration lease, Instant now) {
        return Mono.fromFuture(() -> dynamoDb.putItem(PutItemRequest.builder()
                        .tableName(tableName)
                        .item(EntitySchemas.DISTRIBUTED_LOCK.itemToMap(lease(lockName, owner, now.plus(lease)), true))
                        .conditionExpression("attribute_not_exists(lockName) OR leaseUntil < :now OR #owner = :owner")
                        .expressionAttributeNames(OWNER_NAME)
                        .expressionAttributeValues(Map.of(":now", epochMillis(now), ":owner", string(owner)))
                        .build()))
                .thenReturn(true)
                .onErrorReturn(ConditionalCheckFailedException.class, false);
    }

    @Override
    public Mono<Void> release(String lockName, String owner) {
        return Mono.fromFuture(() -> dynamoDb.deleteItem(DeleteItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("lockName", string(lockName)))
                        .conditionExpression("#owner = :owner")
                        .expressionAttributeNames(OWNER_NAME)
                        .expressionAttributeValues(Map.of(":owner", string(owner)))
                        .build()))
                .then()
                .onErrorResume(ConditionalCheckFailedException.class, lostLease -> Mono.empty());
    }

    private static DistributedLockEntity lease(String lockName, String owner, Instant leaseUntil) {
        DistributedLockEntity entity = new DistributedLockEntity();
        entity.setLockName(lockName);
        entity.setOwner(owner);
        entity.setLeaseUntil(leaseUntil.toEpochMilli());
        entity.setExpiresAt(ceilEpochSeconds(leaseUntil));
        return entity;
    }

    private static long ceilEpochSeconds(Instant instant) {
        return instant.getNano() == 0 ? instant.getEpochSecond() : instant.getEpochSecond() + 1;
    }
}
