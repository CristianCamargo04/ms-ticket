package com.ticketing.business.usecase;

import com.ticketing.domain.data.SweepResult;
import reactor.core.publisher.Mono;

/**
 * Sweeper releasing expired holds and reconciling timed-out payment confirmations.
 */
public interface ReleaseExpiredReservationsUseCase {

    /**
     * Runs one sweep under the {@code reservation-expiration-sweeper} lease; per-order failures are counted, never
     * propagated.
     *
     * @return the sweep counters, or {@link SweepResult#skipped()} when the lease is held by another instance
     */
    Mono<SweepResult> execute();
}
