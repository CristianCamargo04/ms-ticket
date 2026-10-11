package com.ticketing.persistence;

import java.time.Duration;
import java.time.Instant;
import reactor.core.publisher.Mono;

/**
 * Lease-based distributed lock stored in the {@code distributed-locks} table (with a DynamoDB TTL on
 * {@code expiresAt} as garbage collection).
 */
public interface DistributedLockPersistence {

    /**
     * Acquires or renews a lease with a conditional put: the lock does not exist, its lease is over
     * ({@code leaseUntil < now}) or it is already owned by {@code owner}.
     *
     * @param lockName the lock name
     * @param owner    the requesting instance
     * @param lease    lease duration
     * @param now      the reference instant
     * @return {@code true} when acquired, {@code false} when another owner holds a valid lease
     */
    Mono<Boolean> tryAcquire(String lockName, String owner, Duration lease, Instant now);

    /**
     * Releases a lease with a conditional delete ({@code owner = :owner}); a failed condition (lease taken over by
     * another instance) is swallowed.
     *
     * @param lockName the lock name
     * @param owner    the releasing instance
     * @return completion
     */
    Mono<Void> release(String lockName, String owner);
}
