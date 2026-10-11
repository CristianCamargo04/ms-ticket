package com.ticketing.business.usecase;

import com.ticketing.domain.data.SweepResult;
import reactor.core.publisher.Mono;

/**
 * Sweeper republishing the reservation message of orders still queued after their dispatch deadline
 * (dual-write mitigation).
 */
public interface RepublishStaleOrdersUseCase {

    /**
     * Runs one sweep under the {@code order-dispatch-sweeper} lease; per-order failures are counted, never
     * propagated.
     *
     * @return the sweep counters, or {@link SweepResult#skipped()} when the lease is held by another instance
     */
    Mono<SweepResult> execute();
}
