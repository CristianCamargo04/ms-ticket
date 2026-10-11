package com.ticketing.domain.inventory;

import com.ticketing.domain.validation.DomainPreconditions;
import java.time.Duration;

public record SweeperPolicy(Duration lease, int batchSize, int concurrency, String instanceId) {
    public SweeperPolicy {
        DomainPreconditions.requirePositive(lease, "lease");
        DomainPreconditions.requireBetween(batchSize, 1, Integer.MAX_VALUE, "batchSize");
        DomainPreconditions.requireBetween(concurrency, 1, Integer.MAX_VALUE, "concurrency");
        DomainPreconditions.requireNonBlank(instanceId, "instanceId");
    }
}
