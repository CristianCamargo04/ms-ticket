package com.ticketing.persistence;

import com.ticketing.domain.inventory.ConfirmInventoryCommand;
import com.ticketing.domain.inventory.FinalizeSaleCommand;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import com.ticketing.domain.inventory.IssueComplimentaryCommand;
import com.ticketing.domain.inventory.ReleaseInventoryCommand;
import com.ticketing.domain.inventory.ReserveInventoryCommand;
import reactor.core.publisher.Mono;

/**
 * Atomic inventory changes: each call is exactly one DynamoDB {@code TransactWriteItems} updating the event
 * counters, the tickets, the order and writing one audit record per ticket.
 *
 * <p>Business conflicts are returned as {@link InventoryWriteOutcome} values ({@code Applied},
 * {@code InsufficientInventory}, {@code StaleTicketSelection}, {@code OrderStateChanged}). Transaction conflicts,
 * throttling and 5xx errors are error signals {@code InventoryConflictException} (retryable).
 */
public interface InventoryTransactionPersistence {

    /**
     * Reserves the selected tickets for a queued standard order.
     *
     * @param command the command
     * @return the outcome
     */
    Mono<InventoryWriteOutcome> reserve(ReserveInventoryCommand command);

    /**
     * Issues the selected tickets to a queued complimentary order.
     *
     * @param command the command
     * @return the outcome
     */
    Mono<InventoryWriteOutcome> issueComplimentary(IssueComplimentaryCommand command);

    /**
     * Moves the reserved tickets of an order to payment confirmation.
     *
     * @param command the command
     * @return the outcome
     */
    Mono<InventoryWriteOutcome> confirm(ConfirmInventoryCommand command);

    /**
     * Finalizes the sale of an order whose payment was approved.
     *
     * @param command the command
     * @return the outcome
     */
    Mono<InventoryWriteOutcome> finalizeSale(FinalizeSaleCommand command);

    /**
     * Releases the held tickets of an order back to the inventory.
     *
     * @param command the command
     * @return the outcome
     */
    Mono<InventoryWriteOutcome> release(ReleaseInventoryCommand command);
}
