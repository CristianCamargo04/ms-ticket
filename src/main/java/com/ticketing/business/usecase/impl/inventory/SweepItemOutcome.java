package com.ticketing.business.usecase.impl.inventory;

import com.ticketing.domain.data.SweepResult;

enum SweepItemOutcome {
    RELEASED,

    FINALIZED,

    REPUBLISHED,

    UNCHANGED,

    FAILED,

    ABANDONED;

    SweepResult addTo(SweepResult result) {
        return switch (this) {
            case RELEASED -> new SweepResult(result.lockAcquired(), result.released() + 1, result.finalized(),
                    result.republished(), result.failed());
            case FINALIZED -> new SweepResult(result.lockAcquired(), result.released(), result.finalized() + 1,
                    result.republished(), result.failed());
            case REPUBLISHED -> new SweepResult(result.lockAcquired(), result.released(), result.finalized(),
                    result.republished() + 1, result.failed());
            case FAILED, ABANDONED -> new SweepResult(result.lockAcquired(), result.released(), result.finalized(),
                    result.republished(), result.failed() + 1);
            case UNCHANGED -> result;
        };
    }
}
