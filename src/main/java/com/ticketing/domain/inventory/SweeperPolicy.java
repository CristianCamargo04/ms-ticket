package com.ticketing.domain.inventory;

import com.ticketing.domain.validation.DomainPreconditions;
import java.time.Duration;

/**
 * Settings of the sweepers (expired reservations, payment reconciliation, stale order dispatch).
 *
 * @param lease       duration of the distributed lease acquired for one run
 * @param batchSize   maximum due orders read per deadline type and run, {@code >= 1}
 * @param concurrency maximum orders processed in parallel, {@code >= 1}
 * @param instanceId  unique identifier of this instance (lease owner)
 */
public record SweeperPolicy(Duration lease, int batchSize, int concurrency, String instanceId) {

    /**
     * Validates the settings.
     *
     * @throws IllegalArgumentException when a value is out of range
     */
    public SweeperPolicy {
        DomainPreconditions.requirePositive(lease, "lease");
        DomainPreconditions.requireBetween(batchSize, 1, Integer.MAX_VALUE, "batchSize");
        DomainPreconditions.requireBetween(concurrency, 1, Integer.MAX_VALUE, "concurrency");
        DomainPreconditions.requireNonBlank(instanceId, "instanceId");
    }
}
