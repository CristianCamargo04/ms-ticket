package com.ticketing.domain.inventory;

import com.ticketing.domain.data.OrderData;
import java.util.Objects;

public sealed interface InventoryWriteOutcome
        permits InventoryWriteOutcome.Applied, InventoryWriteOutcome.InsufficientInventory,
        InventoryWriteOutcome.StaleTicketSelection, InventoryWriteOutcome.OrderStateChanged {
    record Applied(OrderData order) implements InventoryWriteOutcome {
        public Applied {
            Objects.requireNonNull(order, "order");
        }
    }

    record InsufficientInventory() implements InventoryWriteOutcome {
    }

    record StaleTicketSelection() implements InventoryWriteOutcome {
    }

    record OrderStateChanged() implements InventoryWriteOutcome {
    }
}
