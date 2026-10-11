package com.ticketing.business.usecase.impl.inventory;

import com.ticketing.domain.data.SweepResult;

/**
 * What a sweeper did with one due order; folded into the {@link SweepResult} of the run.
 */
enum SweepItemOutcome {

    /** The held tickets were released back to the inventory. */
    RELEASED,

    /** The sale was finalized during payment reconciliation. */
    FINALIZED,

    /** The reservation message of a queued order was republished. */
    REPUBLISHED,

    /** Nothing to do: the order had already moved on (lost race with a customer or a worker). */
    UNCHANGED,

    /** Processing failed; the order stays due and is retried on the next run. */
    FAILED;

    /**
     * Adds this outcome to the counters of a run.
     *
     * @param result the counters so far
     * @return the counters including this outcome
     */
    SweepResult addTo(SweepResult result) {
        return switch (this) {
            case RELEASED -> new SweepResult(result.lockAcquired(), result.released() + 1, result.finalized(),
                    result.republished(), result.failed());
            case FINALIZED -> new SweepResult(result.lockAcquired(), result.released(), result.finalized() + 1,
                    result.republished(), result.failed());
            case REPUBLISHED -> new SweepResult(result.lockAcquired(), result.released(), result.finalized(),
                    result.republished() + 1, result.failed());
            case FAILED -> new SweepResult(result.lockAcquired(), result.released(), result.finalized(),
                    result.republished(), result.failed() + 1);
            case UNCHANGED -> result;
        };
    }
}
