package com.ticketing.business.usecase.impl.inventory;

import com.ticketing.business.usecase.ReleaseExpiredReservationsUseCase;
import com.ticketing.client.rest.api.PaymentGatewayChargeApi;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.data.SweepResult;
import com.ticketing.domain.inventory.FinalizeSaleCommand;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import com.ticketing.domain.inventory.InventoryWriteOutcome.Applied;
import com.ticketing.domain.inventory.InventoryWriteOutcome.InsufficientInventory;
import com.ticketing.domain.inventory.InventoryWriteOutcome.OrderStateChanged;
import com.ticketing.domain.inventory.InventoryWriteOutcome.StaleTicketSelection;
import com.ticketing.domain.inventory.ReleaseInventoryCommand;
import com.ticketing.domain.inventory.SweeperPolicy;
import com.ticketing.domain.order.PaymentOutcome.Approved;
import com.ticketing.domain.order.PaymentOutcome.Declined;
import com.ticketing.domain.order.PaymentOutcome.NotFound;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import com.ticketing.persistence.DistributedLockPersistence;
import com.ticketing.persistence.InventoryTransactionPersistence;
import com.ticketing.persistence.OrderPersistence;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Sweeper (under the {@value #LOCK_NAME} lease) that:
 * <ol>
 *     <li>releases the tickets of reservations whose hold expired ({@code RESERVED -> AVAILABLE}, order
 *     {@code EXPIRED});</li>
 *     <li>reconciles orders whose payment confirmation timed out: the gateway is asked for the charge (idempotency key
 *     = order id); a captured charge finalizes the sale, otherwise the tickets are released (never released
 *     blindly, so a captured payment is not lost).</li>
 * </ol>
 * Due orders are processed with bounded concurrency ({@link SweeperPolicy#concurrency()}); a failing order is logged
 * and counted as failed without aborting the run. Every write is conditional, so a customer confirming exactly at the
 * hold boundary and the sweeper cannot both win.
 */
@Service
public class ReleaseExpiredReservationsUseCaseImpl implements ReleaseExpiredReservationsUseCase {

    /** Lease name, also recorded as the actor of the transitions. */
    static final String LOCK_NAME = "reservation-expiration-sweeper";

    private static final Logger LOGGER = LoggerFactory.getLogger(ReleaseExpiredReservationsUseCaseImpl.class);

    private final OrderPersistence orderPersistence;
    private final InventoryTransactionPersistence inventoryTransactionPersistence;
    private final PaymentGatewayChargeApi paymentGatewayChargeApi;
    private final SweeperPolicy sweeperPolicy;
    private final LeasedSweep leasedSweep;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param distributedLockPersistence      lease storage
     * @param orderPersistence                orders table (deadline index)
     * @param inventoryTransactionPersistence inventory transactions
     * @param paymentGatewayChargeApi         payment gateway (charge lookup)
     * @param sweeperPolicy                   sweeper settings
     * @param clock                           time source
     */
    public ReleaseExpiredReservationsUseCaseImpl(DistributedLockPersistence distributedLockPersistence,
                                                 OrderPersistence orderPersistence,
                                                 InventoryTransactionPersistence inventoryTransactionPersistence,
                                                 PaymentGatewayChargeApi paymentGatewayChargeApi,
                                                 SweeperPolicy sweeperPolicy, Clock clock) {
        this.orderPersistence = orderPersistence;
        this.inventoryTransactionPersistence = inventoryTransactionPersistence;
        this.paymentGatewayChargeApi = paymentGatewayChargeApi;
        this.sweeperPolicy = sweeperPolicy;
        this.leasedSweep = new LeasedSweep(distributedLockPersistence, sweeperPolicy);
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<SweepResult> execute() {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            return leasedSweep.run(LOCK_NAME, now, () -> Flux.concat(
                    dueOrders(DeadlineTypeEnum.HOLD_EXPIRATION, now)
                            .flatMap(order -> isolated(order, expireHold(order, now)), sweeperPolicy.concurrency()),
                    dueOrders(DeadlineTypeEnum.PAYMENT_CONFIRMATION, now)
                            .flatMap(order -> isolated(order, reconcilePayment(order, now)),
                                    sweeperPolicy.concurrency())));
        });
    }

    private Flux<OrderData> dueOrders(DeadlineTypeEnum type, Instant now) {
        return Flux.defer(() -> orderPersistence.findDueOrders(type, now, sweeperPolicy.batchSize()));
    }

    private Mono<SweepItemOutcome> expireHold(OrderData order, Instant now) {
        return Mono.defer(() -> inventoryTransactionPersistence.release(new ReleaseInventoryCommand(order,
                        TicketStatusEnum.RESERVED, OrderProcessingStatusEnum.EXPIRED, TransitionReasonEnum.HOLD_EXPIRED,
                        now, LOCK_NAME)))
                .map(outcome -> classify(order, outcome, SweepItemOutcome.RELEASED));
    }

    private Mono<SweepItemOutcome> reconcilePayment(OrderData order, Instant now) {
        return Mono.defer(() -> paymentGatewayChargeApi.findCharge(order.orderId()))
                .flatMap(payment -> switch (payment) {
                    case Approved(String paymentReference) -> inventoryTransactionPersistence.finalizeSale(
                                    new FinalizeSaleCommand(order, paymentReference, now, LOCK_NAME))
                            .map(outcome -> classify(order, outcome, SweepItemOutcome.FINALIZED));
                    case Declined _ -> releaseUnpaid(order, now);
                    case NotFound _ -> releaseUnpaid(order, now);
                });
    }

    private Mono<SweepItemOutcome> releaseUnpaid(OrderData order, Instant now) {
        return inventoryTransactionPersistence.release(new ReleaseInventoryCommand(order,
                        TicketStatusEnum.PENDING_CONFIRMATION, OrderProcessingStatusEnum.EXPIRED,
                        TransitionReasonEnum.CONFIRMATION_TIMEOUT, now, LOCK_NAME))
                .map(outcome -> classify(order, outcome, SweepItemOutcome.RELEASED));
    }

    private static SweepItemOutcome classify(OrderData order, InventoryWriteOutcome outcome,
                                             SweepItemOutcome whenApplied) {
        return switch (outcome) {
            case Applied _ -> whenApplied;
            case OrderStateChanged _ -> {
                LOGGER.debug("Order {} changed concurrently, nothing to sweep", order.orderId());
                yield SweepItemOutcome.UNCHANGED;
            }
            case InsufficientInventory _ -> inconsistentTickets(order);
            case StaleTicketSelection _ -> inconsistentTickets(order);
        };
    }

    private static SweepItemOutcome inconsistentTickets(OrderData order) {
        LOGGER.warn("Tickets of order {} are not in the state of the order, sweep failed", order.orderId());
        return SweepItemOutcome.FAILED;
    }

    private static Mono<SweepItemOutcome> isolated(OrderData order, Mono<SweepItemOutcome> processing) {
        return processing.onErrorResume(error -> {
            LOGGER.warn("Sweep of order {} failed, it is retried on the next run: {}", order.orderId(),
                    error.toString());
            return Mono.just(SweepItemOutcome.FAILED);
        });
    }
}
