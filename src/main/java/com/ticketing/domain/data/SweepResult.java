package com.ticketing.domain.data;

import com.ticketing.domain.validation.DomainPreconditions;

/**
 * Outcome of one sweeper run.
 *
 * @param lockAcquired whether this instance held the distributed lease and actually swept
 * @param released     orders whose tickets were released back to the inventory
 * @param finalized    orders whose sale was finalized during payment reconciliation
 * @param republished  queued orders whose reservation message was republished
 * @param failed       orders whose processing failed (logged, retried on the next run)
 */
public record SweepResult(boolean lockAcquired, int released, int finalized, int republished, int failed) {

    /**
     * Validates non-negative counters.
     */
    public SweepResult {
        DomainPreconditions.requireNonNegative(released, "released");
        DomainPreconditions.requireNonNegative(finalized, "finalized");
        DomainPreconditions.requireNonNegative(republished, "republished");
        DomainPreconditions.requireNonNegative(failed, "failed");
    }

    /**
     * Result of a run that did not acquire the lease (or could not run).
     *
     * @return {@code lockAcquired = false} and every counter at zero
     */
    public static SweepResult skipped() {
        return new SweepResult(false, 0, 0, 0, 0);
    }
}
