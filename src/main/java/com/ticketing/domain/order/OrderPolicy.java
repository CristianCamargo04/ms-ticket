package com.ticketing.domain.order;

import com.ticketing.domain.inventory.InventoryCommand;
import com.ticketing.domain.validation.DomainPreconditions;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Business rules of orders (instantiated as a bean by the composition root from {@code ticketing.orders.*}).
 *
 * @param holdTtl                    how long reserved tickets are held for the customer (default 10m)
 * @param confirmationTimeout        how long a confirmed order may wait for its payment result (default 5m)
 * @param republishAfter             delay after which a still-queued order is republished (default 30s)
 * @param maxTicketsPerOrder         maximum tickets per standard order (default 10), at most
 *                                   {@link InventoryCommand#MAX_TICKETS_PER_TRANSACTION}
 * @param maxComplimentaryPerRequest maximum tickets per complimentary request (default 40), at most
 *                                   {@link InventoryCommand#MAX_TICKETS_PER_TRANSACTION}
 * @param candidateFactor            candidate tickets read per requested ticket when selecting tickets (default 3)
 */
public record OrderPolicy(Duration holdTtl, Duration confirmationTimeout, Duration republishAfter,
                          int maxTicketsPerOrder, int maxComplimentaryPerRequest, int candidateFactor) {

    /**
     * Validates the settings.
     *
     * @throws IllegalArgumentException when a value is out of range
     */
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

    /**
     * End of the hold of a reservation made at {@code now}.
     *
     * @param now reservation instant
     * @return {@code now + holdTtl}
     */
    public Instant holdExpiresAt(Instant now) {
        return Objects.requireNonNull(now, "now").plus(holdTtl);
    }

    /**
     * Payment reconciliation deadline of a confirmation made at {@code now}.
     *
     * @param now confirmation instant
     * @return {@code now + confirmationTimeout}
     */
    public Instant confirmationDeadline(Instant now) {
        return Objects.requireNonNull(now, "now").plus(confirmationTimeout);
    }

    /**
     * Instant after which a queued order is considered not dispatched and is republished.
     *
     * @param now queueing (or republishing) instant
     * @return {@code now + republishAfter}
     */
    public Instant dispatchDeadline(Instant now) {
        return Objects.requireNonNull(now, "now").plus(republishAfter);
    }
}
