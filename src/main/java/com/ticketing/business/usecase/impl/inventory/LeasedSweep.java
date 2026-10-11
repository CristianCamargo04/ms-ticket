package com.ticketing.business.usecase.impl.inventory;

import com.ticketing.domain.data.SweepResult;
import com.ticketing.domain.inventory.SweeperPolicy;
import com.ticketing.persistence.DistributedLockPersistence;
import java.time.Instant;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Slf4j
final class LeasedSweep {

    private final DistributedLockPersistence distributedLockPersistence;
    private final SweeperPolicy sweeperPolicy;

    Mono<SweepResult> run(String lockName, Instant now, Supplier<Flux<SweepItemOutcome>> work) {
        String owner = sweeperPolicy.instanceId();
        return Mono.usingWhen(
                Mono.defer(() -> distributedLockPersistence.tryAcquire(lockName, owner, sweeperPolicy.lease(), now))
                        .defaultIfEmpty(Boolean.FALSE),
                acquired -> acquired ? sweep(lockName, work) : skipped(lockName),
                acquired -> release(lockName, owner, acquired),
                (acquired, error) -> release(lockName, owner, acquired),
                acquired -> release(lockName, owner, acquired));
    }

    private static Mono<SweepResult> sweep(String lockName, Supplier<Flux<SweepItemOutcome>> work) {
        return Flux.defer(work)
                .reduce(new SweepResult(true, 0, 0, 0, 0), (result, outcome) -> outcome.addTo(result))
                .doOnNext(result -> log.info("Sweep {} finished: released={}, finalized={}, republished={},"
                        + " failed={}", lockName, result.released(), result.finalized(), result.republished(),
                        result.failed()));
    }

    private static Mono<SweepResult> skipped(String lockName) {
        log.debug("Sweep {} skipped: the lease is held by another instance", lockName);
        return Mono.just(SweepResult.skipped());
    }

    private Mono<Void> release(String lockName, String owner, boolean acquired) {
        if (!acquired) {
            return Mono.empty();
        }
        return Mono.defer(() -> distributedLockPersistence.release(lockName, owner))
                .onErrorResume(error -> {
                    log.warn("Lease {} not released, it will expire on its own: {}", lockName, error.toString());
                    return Mono.empty();
                });
    }
}
