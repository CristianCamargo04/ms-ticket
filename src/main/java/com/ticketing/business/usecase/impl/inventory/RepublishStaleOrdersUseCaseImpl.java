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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class RepublishStaleOrdersUseCaseImpl implements RepublishStaleOrdersUseCase {
    static final String LOCK_NAME = "order-dispatch-sweeper";

    static final int MAX_DISPATCH_ROUNDS = 2_880;

    static final String DISPATCH_EXHAUSTED = "DISPATCH_EXHAUSTED";

    private final OrderPersistence orderPersistence;
    private final SqsOrderQueueClient sqsOrderQueueClient;
    private final OrderPolicy orderPolicy;
    private final SweeperPolicy sweeperPolicy;
    private final LeasedSweep leasedSweep;
    private final Duration maxDispatchAge;
    private final Clock clock;

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
            log.warn("Dispatch sweep of order {} failed, it is retried on the next run: {}", order.orderId(),
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
                        log.debug("Order {} left the queue meanwhile, republication was not needed",
                                order.orderId());
                        return SweepItemOutcome.UNCHANGED;
                    }
                    log.info("Reservation message of order {} republished", order.orderId());
                    return SweepItemOutcome.REPUBLISHED;
                });
    }

    private Mono<SweepItemOutcome> giveUp(OrderData order, Instant now) {
        return Mono.defer(() -> orderPersistence.transitionProcessingStatus(order.orderId(),
                        OrderProcessingStatusEnum.QUEUED, OrderProcessingStatusEnum.FAILED, DISPATCH_EXHAUSTED, now))
                .map(failed -> {
                    if (!failed) {
                        log.debug("Order {} left the queue meanwhile, nothing to give up", order.orderId());
                        return SweepItemOutcome.UNCHANGED;
                    }
                    log.error("Order {} still queued {} after its creation, marked FAILED ({})", order.orderId(),
                            maxDispatchAge, DISPATCH_EXHAUSTED);
                    return SweepItemOutcome.ABANDONED;
                });
    }
}
