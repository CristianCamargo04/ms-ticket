package com.ticketing.persistence;

import com.ticketing.domain.inventory.ConfirmInventoryCommand;
import com.ticketing.domain.inventory.FinalizeSaleCommand;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import com.ticketing.domain.inventory.IssueComplimentaryCommand;
import com.ticketing.domain.inventory.ReleaseInventoryCommand;
import com.ticketing.domain.inventory.ReserveInventoryCommand;
import reactor.core.publisher.Mono;

public interface InventoryTransactionPersistence {
    Mono<InventoryWriteOutcome> reserve(ReserveInventoryCommand command);

    Mono<InventoryWriteOutcome> issueComplimentary(IssueComplimentaryCommand command);

    Mono<InventoryWriteOutcome> confirm(ConfirmInventoryCommand command);

    Mono<InventoryWriteOutcome> finalizeSale(FinalizeSaleCommand command);

    Mono<InventoryWriteOutcome> release(ReleaseInventoryCommand command);
}
