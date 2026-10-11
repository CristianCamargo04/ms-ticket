package com.ticketing.business.usecase.impl.inventory;

import com.ticketing.business.usecase.RepublishStaleOrdersUseCase;
import com.ticketing.client.sdk.SqsOrderQueueClient;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.data.SweepResult;
import com.ticketing.domain.internal_request.OrderReservationMessage;
import com.ticketing.domain.inventory.SweeperPolicy;
import com.ticketing.domain.order.OrderPolicy;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.persistence.DistributedLockPersistence;
import com.ticketing.persistence.OrderPersistence;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Sweeper (under the {@value #LOCK_NAME} lease) mitigating the dual write of order acceptance (DynamoDB + SQS): orders
 * still {@code QUEUED} after their dispatch deadline get their reservation message republished and their deadline moved
 * forward by {@link OrderPolicy#republishAfter()}. Republishing is safe because the reservation consumer is idempotent.
 * The run itself is the retry loop: a failed publication leaves the deadline due for the next run.
 *
 * <p>Retries are bounded: an order still queued {@value #MAX_DISPATCH_ROUNDS} republish delays after its creation is a
 * poison order (its messages keep failing and end in the dead-letter queue), so instead of re-injecting it forever
 * it is moved to {@code FAILED} with reason {@value #DISPATCH_EXHAUSTED} (counted as failed and logged as an ERROR for
 * the operators). A republished order that left the queue meanwhile is counted as unchanged.
 */
@Service
public class RepublishStaleOrdersUseCaseImpl implements RepublishStaleOrdersUseCase {

    /** Lease name. */
    static final String LOCK_NAME = "order-dispatch-sweeper";

    /**
     * Republish delays ({@link OrderPolicy#republishAfter()}) after which a still-queued order is given up: 24 hours
     * with the default 30 seconds. The window is deliberately much longer than any realistic worker outage or queue
     * backlog, so a legitimately queued order is never failed just because the consumers were down for a while
     * (the reservation queue retains messages for 4 days).
     */
    static final int MAX_DISPATCH_ROUNDS = 2_880;

    /** Reason recorded on an order given up after {@link #MAX_DISPATCH_ROUNDS}. */
    static final String DISPATCH_EXHAUSTED = "DISPATCH_EXHAUSTED";

    private static final Logger LOGGER = LoggerFactory.getLogger(RepublishStaleOrdersUseCaseImpl.class);

    private final OrderPersistence orderPersistence;
    private final SqsOrderQueueClient sqsOrderQueueClient;
    private final OrderPolicy orderPolicy;
    private final SweeperPolicy sweeperPolicy;
    private final LeasedSweep leasedSweep;
    private final Duration maxDispatchAge;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param distributedLockPersistence lease storage
     * @param orderPersistence           orders table (deadline index)
     * @param sqsOrderQueueClient        order queues
     * @param orderPolicy                order rules (dispatch deadline)
     * @param sweeperPolicy              sweeper settings
     * @param clock                      time source
     */
    public RepublishStaleOrdersUseCaseImpl(DistributedLockPersistence distributedLockPersistence,
                                           OrderPersistence orderPersistence, SqsOrderQueueClient sqsOrderQueueClient,
                                           OrderPolicy orderPolicy, SweeperPolicy sweeperPolicy, Clock clock) {
        this.orderPersistence = orderPersistence;
        this.sqsOrderQueueClient = sqsOrderQueueClient;
        this.orderPolicy = orderPolicy;
        this.sweeperPolicy = sweeperPolicy;
        this.leasedSweep = new LeasedSweep(distributedLockPersistence, sweeperPolicy);
        this.maxDispatchAge = orderPolicy.republishAfter().multipliedBy(MAX_DISPATCH_ROUNDS);
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<SweepResult> execute() {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            return leasedSweep.run(LOCK_NAME, now, () -> Flux.defer(() -> orderPersistence.findDueOrders(
                            DeadlineTypeEnum.DISPATCH, now, sweeperPolicy.batchSize()))
                    .flatMap(order -> dispatch(order, now), sweeperPolicy.concurrency()));
        });
    }

    private Mono<SweepItemOutcome> dispatch(OrderData order, Instant now) {
        Mono<SweepItemOutcome> processing = now.isBefore(order.createdAt().plus(maxDispatchAge))
                ? republish(order, now)
                : giveUp(order, now);
        return processing.onErrorResume(error -> {
            LOGGER.warn("Dispatch sweep of order {} failed, it is retried on the next run: {}", order.orderId(),
                    error.toString());
            return Mono.just(SweepItemOutcome.FAILED);
        });
    }

    private Mono<SweepItemOutcome> republish(OrderData order, Instant now) {
        return Mono.defer(() -> sqsOrderQueueClient.publishReservationRequested(new OrderReservationMessage(
                        order.orderId(), order.eventId(), order.orderId(), now)))
                .then(Mono.defer(() -> orderPersistence.rescheduleDeadline(order.orderId(),
                        OrderProcessingStatusEnum.QUEUED, orderPolicy.dispatchDeadline(now), now)))
                .map(rescheduled -> {
                    if (!rescheduled) {
                        LOGGER.debug("Order {} left the queue meanwhile, republication was not needed",
                                order.orderId());
                        return SweepItemOutcome.UNCHANGED;
                    }
                    LOGGER.info("Reservation message of order {} republished", order.orderId());
                    return SweepItemOutcome.REPUBLISHED;
                });
    }

    private Mono<SweepItemOutcome> giveUp(OrderData order, Instant now) {
        return Mono.defer(() -> orderPersistence.transitionProcessingStatus(order.orderId(),
                        OrderProcessingStatusEnum.QUEUED, OrderProcessingStatusEnum.FAILED, DISPATCH_EXHAUSTED, now))
                .map(failed -> {
                    if (!failed) {
                        LOGGER.debug("Order {} left the queue meanwhile, nothing to give up", order.orderId());
                        return SweepItemOutcome.UNCHANGED;
                    }
                    LOGGER.error("Order {} still queued {} after its creation, marked FAILED ({})", order.orderId(),
                            maxDispatchAge, DISPATCH_EXHAUSTED);
                    return SweepItemOutcome.ABANDONED;
                });
    }
}
