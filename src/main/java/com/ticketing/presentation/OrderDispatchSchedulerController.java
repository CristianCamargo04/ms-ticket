package com.ticketing.presentation;

import com.ticketing.domain.data.SweepResult;
import reactor.core.publisher.Mono;

/**
 * Timer channel of the stale-order dispatch sweeper (triggered by the scheduling config).
 */
public interface OrderDispatchSchedulerController {

    /**
     * Runs one sweep and logs its result.
     *
     * @return the sweep result; never an error signal (errors are logged and mapped to
     *         {@link SweepResult#skipped()} so the timer keeps running)
     */
    Mono<SweepResult> execute();
}
