package com.ticketing.persistence.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.persistence.support.PersistenceTestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

class DistributedLockPersistenceImplIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration LEASE = Duration.ofSeconds(30);

    private static PersistenceTestContext context;

    @BeforeAll
    static void createTables() {
        context = PersistenceTestContext.create();
    }

    @Test
    void shouldGrantTheLeaseToOneOwnerAtATime() {
        String lock = "sweeper-" + UUID.randomUUID();
        Instant now = PersistenceTestContext.now();

        StepVerifier.create(context.locks().tryAcquire(lock, "instance-a", LEASE, now)).expectNext(true)
                .verifyComplete();
        StepVerifier.create(context.locks().tryAcquire(lock, "instance-b", LEASE, now.plusSeconds(1)))
                .expectNext(false).verifyComplete();
        StepVerifier.create(context.locks().tryAcquire(lock, "instance-a", LEASE, now.plusSeconds(2)))
                .as("the owner renews its lease").expectNext(true).verifyComplete();
        StepVerifier.create(context.locks().tryAcquire(lock, "instance-b", LEASE, now.plusSeconds(31)))
                .as("the renewed lease is still valid").expectNext(false).verifyComplete();
        StepVerifier.create(context.locks().tryAcquire(lock, "instance-b", LEASE, now.plusSeconds(33)))
                .as("an expired lease can be taken over").expectNext(true).verifyComplete();

        Map<String, AttributeValue> item = rawItem(lock);
        assertThat(item.get("owner").s()).isEqualTo("instance-b");
        assertThat(item.get("leaseUntil").n()).isEqualTo(Long.toString(now.plusSeconds(63).toEpochMilli()));
        assertThat(Long.parseLong(item.get("expiresAt").n())).isEqualTo(now.plusSeconds(63).getEpochSecond()
                + (now.getNano() == 0 ? 0 : 1));
    }

    @Test
    void shouldReleaseOnlyTheOwnLease() {
        String lock = "sweeper-" + UUID.randomUUID();
        Instant now = PersistenceTestContext.now();
        context.locks().tryAcquire(lock, "instance-a", LEASE, now).block(TIMEOUT);

        StepVerifier.create(context.locks().release(lock, "instance-b")).verifyComplete();
        assertThat(rawItem(lock)).isNotEmpty();

        StepVerifier.create(context.locks().release(lock, "instance-a")).verifyComplete();
        assertThat(rawItem(lock)).isEmpty();
        StepVerifier.create(context.locks().tryAcquire(lock, "instance-b", LEASE, now.plusSeconds(1)))
                .expectNext(true).verifyComplete();
        StepVerifier.create(context.locks().release("never-acquired-" + UUID.randomUUID(), "instance-a"))
                .verifyComplete();
    }

    private static Map<String, AttributeValue> rawItem(String lock) {
        return context.client().getItem(GetItemRequest.builder()
                .tableName(context.tableNames().distributedLocks())
                .key(Map.of("lockName", AttributeValue.fromS(lock)))
                .consistentRead(true)
                .build()).join().item();
    }
}
