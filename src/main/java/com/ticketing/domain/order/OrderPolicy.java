package com.ticketing.domain.order;

import com.ticketing.domain.inventory.InventoryCommand;
import com.ticketing.domain.validation.DomainPreconditions;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record OrderPolicy(Duration holdTtl, Duration confirmationTimeout, Duration republishAfter,
                          int maxTicketsPerOrder, int maxComplimentaryPerRequest, int candidateFactor) {
    public OrderPolicy {
        DomainPreconditions.requirePositive(holdTtl, "holdTtl");
        DomainPreconditions.requirePositive(confirmationTimeout, "confirmationTimeout");
        DomainPreconditions.requirePositive(republishAfter, "republishAfter");
        DomainPreconditions.requireBetween(maxTicketsPerOrder, 1, InventoryCommand.MAX_TICKETS_PER_TRANSACTION,
                "maxTicketsPerOrder");
        DomainPreconditions.requireBetween(maxComplimentaryPerRequest, 1,
                InventoryCommand.MAX_TICKETS_PER_TRANSACTION, "maxComplimentaryPerRequest");
        DomainPreconditions.requireBetween(candidateFactor, 1, Integer.MAX_VALUE, "candidateFactor");
    }

    public Instant holdExpiresAt(Instant now) {
        return Objects.requireNonNull(now, "now").plus(holdTtl);
    }

    public Instant confirmationDeadline(Instant now) {
        return Objects.requireNonNull(now, "now").plus(confirmationTimeout);
    }

    public Instant dispatchDeadline(Instant now) {
        return Objects.requireNonNull(now, "now").plus(republishAfter);
    }
}
