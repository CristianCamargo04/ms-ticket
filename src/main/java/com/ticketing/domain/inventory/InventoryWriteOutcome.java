package com.ticketing.domain.inventory;

import com.ticketing.domain.data.OrderData;
import java.util.Objects;

/**
 * Result of an inventory transaction. Business conflicts are values (pattern-match them with an exhaustive
 * {@code switch}); transient technical failures are error signals ({@code InventoryConflictException}).
 */
public sealed interface InventoryWriteOutcome
        permits InventoryWriteOutcome.Applied, InventoryWriteOutcome.InsufficientInventory,
        InventoryWriteOutcome.StaleTicketSelection, InventoryWriteOutcome.OrderStateChanged {

    /**
     * The transaction committed.
     *
     * @param order the input order with the new state applied in memory ({@code version + 1})
     */
    record Applied(OrderData order) implements InventoryWriteOutcome {

        /**
         * Validates that the order is present.
         */
        public Applied {
            Objects.requireNonNull(order, "order");
        }
    }

    /**
     * The event counter condition failed: not enough available tickets (or the event is not on sale).
     */
    record InsufficientInventory() implements InventoryWriteOutcome {
    }

    /**
     * A ticket was no longer in the expected state (stale selection read from an eventually consistent index).
     */
    record StaleTicketSelection() implements InventoryWriteOutcome {
    }

    /**
     * The order condition failed: it was already processed or changed concurrently.
     */
    record OrderStateChanged() implements InventoryWriteOutcome {
    }
}
