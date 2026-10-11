package com.ticketing.persistence;

import java.time.Duration;
import java.time.Instant;
import reactor.core.publisher.Mono;

public interface DistributedLockPersistence {
    Mono<Boolean> tryAcquire(String lockName, String owner, Duration lease, Instant now);

    Mono<Void> release(String lockName, String owner);
}
