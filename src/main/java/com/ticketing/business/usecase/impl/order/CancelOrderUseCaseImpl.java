package com.ticketing.business.usecase.impl.order;

import com.ticketing.business.usecase.CancelOrderUseCase;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.exception.InvalidOrderStateException;
import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.exception.OrderNotFoundException;
import com.ticketing.domain.internal_request.CancelOrderRequest;
import com.ticketing.domain.inventory.InventoryWriteOutcome.Applied;
import com.ticketing.domain.inventory.InventoryWriteOutcome.InsufficientInventory;
import com.ticketing.domain.inventory.InventoryWriteOutcome.OrderStateChanged;
import com.ticketing.domain.inventory.InventoryWriteOutcome.StaleTicketSelection;
import com.ticketing.domain.inventory.ReleaseInventoryCommand;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import com.ticketing.persistence.InventoryTransactionPersistence;
import com.ticketing.persistence.OrderPersistence;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Cancels an order at its owner's request (idempotent).
 *
 * <ul>
 *     <li>{@code QUEUED}: conditional {@code QUEUED -> CANCELLED} (nothing is held yet).</li>
 *     <li>{@code ACTIVE} with {@code RESERVED} tickets: release transaction back to the inventory.</li>
 *     <li>Already {@code CANCELLED}: returned as is.</li>
 *     <li>Anything else (pending payment, sold, expired, ...): {@link InvalidOrderStateException}.</li>
 * </ul>
 *
 * <p>When the conditional write loses a race (e.g. the worker reserved the queued order meanwhile) the order is
 * reloaded and the decision is taken again on its current state, at most {@value #MAX_REEVALUATIONS} times.
 */
@Service
public class CancelOrderUseCaseImpl implements CancelOrderUseCase {

    /** How many times a cancellation lost to a concurrent change is re-evaluated on the reloaded order. */
    static final int MAX_REEVALUATIONS = 2;

    private static final Logger LOGGER = LoggerFactory.getLogger(CancelOrderUseCaseImpl.class);
    private static final String REASON = TransitionReasonEnum.CUSTOMER_CANCELLATION.name();

    private final OrderPersistence orderPersistence;
    private final InventoryTransactionPersistence inventoryTransactionPersistence;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param orderPersistence                orders table
     * @param inventoryTransactionPersistence inventory transactions
     * @param clock                           time source
     */
    public CancelOrderUseCaseImpl(OrderPersistence orderPersistence,
                                  InventoryTransactionPersistence inventoryTransactionPersistence, Clock clock) {
        this.orderPersistence = orderPersistence;
        this.inventoryTransactionPersistence = inventoryTransactionPersistence;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<OrderData> execute(CancelOrderRequest request) {
        return Mono.defer(() -> orderPersistence.findById(request.orderId()))
                .filter(order -> order.isOwnedBy(request.customerId()))
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(request.orderId())))
                .flatMap(order -> cancel(order, request.customerId(), MAX_REEVALUATIONS));
    }

    private Mono<OrderData> cancel(OrderData order, String customerId, int reevaluationsLeft) {
        OrderProcessingStatusEnum status = order.processingStatus();
        if (status == OrderProcessingStatusEnum.CANCELLED) {
            return Mono.just(order);
        }
        Instant now = clock.instant();
        if (status == OrderProcessingStatusEnum.QUEUED) {
            return Mono.defer(() -> orderPersistence.transitionProcessingStatus(order.orderId(),
                            OrderProcessingStatusEnum.QUEUED, OrderProcessingStatusEnum.CANCELLED, REASON, now))
                    .flatMap(cancelled -> cancelled
                            ? cancelled(order.orderId())
                            : reevaluate(order.orderId(), customerId, reevaluationsLeft));
        }
        if (status == OrderProcessingStatusEnum.ACTIVE && order.ticketStatus() == TicketStatusEnum.RESERVED) {
            return Mono.defer(() -> inventoryTransactionPersistence.release(new ReleaseInventoryCommand(order,
                            TicketStatusEnum.RESERVED, OrderProcessingStatusEnum.CANCELLED,
                            TransitionReasonEnum.CUSTOMER_CANCELLATION, now, customerId)))
                    .flatMap(outcome -> switch (outcome) {
                        case Applied(OrderData released) -> {
                            LOGGER.info("Order {} cancelled, {} tickets released", released.orderId(),
                                    released.quantity());
                            yield Mono.just(released);
                        }
                        case OrderStateChanged _ -> reevaluate(order.orderId(), customerId, reevaluationsLeft);
                        case InsufficientInventory _ -> ticketsChanged(order);
                        case StaleTicketSelection _ -> ticketsChanged(order);
                    });
        }
        return Mono.error(new InvalidOrderStateException(order.orderId(), "order is " + status + " with tickets "
                + order.ticketStatus() + " and cannot be cancelled"));
    }

    private static Mono<OrderData> ticketsChanged(OrderData order) {
        return Mono.error(new InventoryConflictException(
                "Tickets of order " + order.orderId() + " changed during the cancellation"));
    }

    private Mono<OrderData> cancelled(String orderId) {
        LOGGER.info("Queued order {} cancelled", orderId);
        return reload(orderId);
    }

    private Mono<OrderData> reevaluate(String orderId, String customerId, int reevaluationsLeft) {
        if (reevaluationsLeft == 0) {
            return Mono.error(new InventoryConflictException(
                    "Order " + orderId + " keeps changing concurrently, retry the cancellation"));
        }
        LOGGER.debug("Order {} changed during its cancellation, re-evaluating", orderId);
        return reload(orderId).flatMap(current -> cancel(current, customerId, reevaluationsLeft - 1));
    }

    private Mono<OrderData> reload(String orderId) {
        return Mono.defer(() -> orderPersistence.findById(orderId))
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId)));
    }
}
