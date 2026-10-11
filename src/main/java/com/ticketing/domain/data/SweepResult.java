package com.ticketing.domain.data;

import com.ticketing.domain.validation.DomainPreconditions;

public record SweepResult(boolean lockAcquired, int released, int finalized, int republished, int failed) {
    public SweepResult {
        DomainPreconditions.requireNonNegative(released, "released");
        DomainPreconditions.requireNonNegative(finalized, "finalized");
        DomainPreconditions.requireNonNegative(republished, "republished");
        DomainPreconditions.requireNonNegative(failed, "failed");
    }

    public static SweepResult skipped() {
        return new SweepResult(false, 0, 0, 0, 0);
    }
}
