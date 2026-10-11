package com.ticketing.persistence.impl;

import static com.ticketing.persistence.PersistenceFixtures.TABLES;
import static com.ticketing.persistence.PersistenceFixtures.completed;
import static com.ticketing.persistence.PersistenceFixtures.failed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

@ExtendWith(MockitoExtension.class)
class DistributedLockPersistenceImplTest {
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00.250Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Mock
    private DynamoDbAsyncClient dynamoDb;

    private DistributedLockPersistenceImpl persistence;

    @BeforeEach
    void setUp() {
        persistence = new DistributedLockPersistenceImpl(dynamoDb, TABLES);
    }

    @Test
    void shouldAcquireTheLeaseWithAConditionalPut() {
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(completed(PutItemResponse.builder().build()));

        StepVerifier.create(persistence.tryAcquire("sweeper", "instance-1", LEASE, NOW))
                .expectNext(true).verifyComplete();

        ArgumentCaptor<PutItemRequest> request = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(request.capture());
        Instant leaseUntil = NOW.plus(LEASE);
        assertThat(request.getValue().tableName()).isEqualTo("t-locks");
        assertThat(request.getValue().item()).isEqualTo(Map.of(
                "lockName", AttributeValue.fromS("sweeper"),
                "owner", AttributeValue.fromS("instance-1"),
                "leaseUntil", AttributeValue.fromN(Long.toString(leaseUntil.toEpochMilli())),
                "expiresAt", AttributeValue.fromN(Long.toString(leaseUntil.getEpochSecond() + 1))));
        assertThat(request.getValue().conditionExpression())
                .isEqualTo("attribute_not_exists(lockName) OR leaseUntil < :now OR #owner = :owner");
        assertThat(request.getValue().expressionAttributeNames()).isEqualTo(Map.of("#owner", "owner"));
        assertThat(request.getValue().expressionAttributeValues()).isEqualTo(Map.of(
                ":now", AttributeValue.fromN(Long.toString(NOW.toEpochMilli())),
                ":owner", AttributeValue.fromS("instance-1")));
    }

    @Test
    void shouldExpireExactlyAtAWholeSecond() {
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(completed(PutItemResponse.builder().build()));
        Instant wholeSecond = Instant.parse("2026-10-10T12:00:00Z");

        persistence.tryAcquire("sweeper", "instance-1", LEASE, wholeSecond).block();

        ArgumentCaptor<PutItemRequest> request = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(request.capture());
        assertThat(request.getValue().item().get("expiresAt").n())
                .isEqualTo(Long.toString(wholeSecond.plus(LEASE).getEpochSecond()));
    }

    @Test
    void shouldNotAcquireALeaseHeldByAnotherOwner() {
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(failed(ConditionalCheckFailedException.builder().message("held").build()));

        StepVerifier.create(persistence.tryAcquire("sweeper", "instance-2", LEASE, NOW))
                .expectNext(false).verifyComplete();
    }

    @Test
    void shouldPropagateAcquisitionFailures() {
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(failed(InternalServerErrorException.builder().message("boom").build()));

        StepVerifier.create(persistence.tryAcquire("sweeper", "instance-2", LEASE, NOW))
                .expectError(InternalServerErrorException.class).verify();
    }

    @Test
    void shouldReleaseOnlyItsOwnLease() {
        when(dynamoDb.deleteItem(any(DeleteItemRequest.class)))
                .thenReturn(completed(DeleteItemResponse.builder().build()));

        StepVerifier.create(persistence.release("sweeper", "instance-1")).verifyComplete();

        ArgumentCaptor<DeleteItemRequest> request = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamoDb).deleteItem(request.capture());
        assertThat(request.getValue().tableName()).isEqualTo("t-locks");
        assertThat(request.getValue().key()).isEqualTo(Map.of("lockName", AttributeValue.fromS("sweeper")));
        assertThat(request.getValue().conditionExpression()).isEqualTo("#owner = :owner");
        assertThat(request.getValue().expressionAttributeNames()).isEqualTo(Map.of("#owner", "owner"));
        assertThat(request.getValue().expressionAttributeValues())
                .isEqualTo(Map.of(":owner", AttributeValue.fromS("instance-1")));
    }

    @Test
    void shouldSwallowTheReleaseOfALeaseTakenOver() {
        when(dynamoDb.deleteItem(any(DeleteItemRequest.class)))
                .thenReturn(failed(ConditionalCheckFailedException.builder().message("taken over").build()));

        StepVerifier.create(persistence.release("sweeper", "instance-1")).verifyComplete();
    }

    @Test
    void shouldPropagateOtherReleaseFailures() {
        when(dynamoDb.deleteItem(any(DeleteItemRequest.class)))
                .thenReturn(failed(InternalServerErrorException.builder().message("boom").build()));

        StepVerifier.create(persistence.release("sweeper", "instance-1"))
                .expectError(InternalServerErrorException.class).verify();
    }
}
