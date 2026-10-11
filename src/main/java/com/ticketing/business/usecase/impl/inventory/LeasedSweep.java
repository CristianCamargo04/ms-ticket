package com.ticketing.business.usecase.impl.inventory;

import com.ticketing.domain.data.SweepResult;
import com.ticketing.domain.inventory.SweeperPolicy;
import com.ticketing.persistence.DistributedLockPersistence;
import java.time.Instant;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Runs one sweep under a distributed lease so that a single instance sweeps at a time: acquire the lease, run the work,
 * and <em>always</em> release the lease afterwards ({@link Mono#usingWhen} covers completion, error and cancellation).
 * Without the lease the run is skipped. Correctness never depends on the lease (every write is conditional); it only
 * avoids duplicated work.
 */
final class LeasedSweep {

    private static final Logger LOGGER = LoggerFactory.getLogger(LeasedSweep.class);

    private final DistributedLockPersistence distributedLockPersistence;
    private final SweeperPolicy sweeperPolicy;

    /**
     * Creates the helper.
     *
     * @param distributedLockPersistence lease storage
     * @param sweeperPolicy              sweeper settings (lease duration, instance id)
     */
    LeasedSweep(DistributedLockPersistence distributedLockPersistence, SweeperPolicy sweeperPolicy) {
        this.distributedLockPersistence = distributedLockPersistence;
        this.sweeperPolicy = sweeperPolicy;
    }

    /**
     * Runs a sweep.
     *
     * @param lockName the lease name
     * @param now      the reference instant of the run
     * @param work     builds the per-order outcomes; each element must already have its failures turned into
     *                 {@link SweepItemOutcome#FAILED}
     * @return the counters of the run, or {@link SweepResult#skipped()} when another instance holds the lease
     */
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
                .doOnNext(result -> LOGGER.info("Sweep {} finished: released={}, finalized={}, republished={},"
                        + " failed={}", lockName, result.released(), result.finalized(), result.republished(),
                        result.failed()));
    }

    private static Mono<SweepResult> skipped(String lockName) {
        LOGGER.debug("Sweep {} skipped: the lease is held by another instance", lockName);
        return Mono.just(SweepResult.skipped());
    }

    private Mono<Void> release(String lockName, String owner, boolean acquired) {
        if (!acquired) {
            return Mono.empty();
        }
        return Mono.defer(() -> distributedLockPersistence.release(lockName, owner))
                .onErrorResume(error -> {
                    LOGGER.warn("Lease {} not released, it will expire on its own: {}", lockName, error.toString());
                    return Mono.empty();
                });
    }
}
