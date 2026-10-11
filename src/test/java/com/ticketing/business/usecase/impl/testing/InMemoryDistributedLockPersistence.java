package com.ticketing.business.usecase.impl.testing;

import com.ticketing.persistence.DistributedLockPersistence;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import reactor.core.publisher.Mono;

public final class InMemoryDistributedLockPersistence implements DistributedLockPersistence {
    private final InMemoryTicketingStore store;
    private final Map<String, Lease> leases = new HashMap<>();

    public InMemoryDistributedLockPersistence(InMemoryTicketingStore store) {
        this.store = store;
    }

    @Override
    public Mono<Boolean> tryAcquire(String lockName, String owner, Duration lease, Instant now) {
        return store.atomically(() -> {
            Lease current = leases.get(lockName);
            if (current == null || current.leaseUntil().isBefore(now) || current.owner().equals(owner)) {
                leases.put(lockName, new Lease(owner, now.plus(lease)));
                return true;
            }
            return false;
        });
    }

    @Override
    public Mono<Void> release(String lockName, String owner) {
        return store.atomically(() -> {
            leases.computeIfPresent(lockName, (_, lease) -> lease.owner().equals(owner) ? null : lease);
            return null;
        }).then();
    }

    public boolean isHeld(String lockName) {
        synchronized (store) {
            return leases.containsKey(lockName);
        }
    }

    private record Lease(String owner, Instant leaseUntil) {
    }
}
