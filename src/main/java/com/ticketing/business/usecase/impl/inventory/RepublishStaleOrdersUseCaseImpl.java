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
 */
@Service
public class RepublishStaleOrdersUseCaseImpl implements RepublishStaleOrdersUseCase {

    /** Lease name. */
    static final String LOCK_NAME = "order-dispatch-sweeper";

    private static final Logger LOGGER = LoggerFactory.getLogger(RepublishStaleOrdersUseCaseImpl.class);

    private final OrderPersistence orderPersistence;
    private final SqsOrderQueueClient sqsOrderQueueClient;
    private final OrderPolicy orderPolicy;
    private final SweeperPolicy sweeperPolicy;
    private final LeasedSweep leasedSweep;
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
                    .flatMap(order -> republish(order, now), sweeperPolicy.concurrency()));
        });
    }

    private Mono<SweepItemOutcome> republish(OrderData order, Instant now) {
        return Mono.defer(() -> sqsOrderQueueClient.publishReservationRequested(new OrderReservationMessage(
                        order.orderId(), order.eventId(), order.orderId(), now)))
                .then(Mono.defer(() -> orderPersistence.rescheduleDeadline(order.orderId(),
                        OrderProcessingStatusEnum.QUEUED, orderPolicy.dispatchDeadline(now), now)))
                .map(rescheduled -> {
                    LOGGER.info("Reservation message of order {} republished (deadline moved: {})", order.orderId(),
                            rescheduled);
                    return SweepItemOutcome.REPUBLISHED;
                })
                .onErrorResume(error -> {
                    LOGGER.warn("Republication of order {} failed, it is retried on the next run: {}",
                            order.orderId(), error.toString());
                    return Mono.just(SweepItemOutcome.FAILED);
                });
    }
}
