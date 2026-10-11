package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.RepublishStaleOrdersUseCase;
import com.ticketing.domain.data.SweepResult;
import com.ticketing.presentation.OrderDispatchSchedulerController;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Timer channel of the stale-order dispatch sweeper (re-publishes orders still queued after the dispatch deadline).
 * Never emits an error: a failed run is logged and reported as {@link SweepResult#skipped()}, so the periodic timer
 * keeps running.
 */
@Component
public class OrderDispatchSchedulerControllerImpl implements OrderDispatchSchedulerController {

    private static final Logger log = LoggerFactory.getLogger(OrderDispatchSchedulerControllerImpl.class);

    private final RepublishStaleOrdersUseCase republishStaleOrdersUseCase;

    /**
     * Creates the controller.
     *
     * @param republishStaleOrdersUseCase the sweeper use case
     */
    public OrderDispatchSchedulerControllerImpl(RepublishStaleOrdersUseCase republishStaleOrdersUseCase) {
        this.republishStaleOrdersUseCase = Objects.requireNonNull(republishStaleOrdersUseCase,
                "republishStaleOrdersUseCase");
    }

    @Override
    public Mono<SweepResult> execute() {
        return Mono.defer(republishStaleOrdersUseCase::execute)
                .doOnNext(OrderDispatchSchedulerControllerImpl::logResult)
                .onErrorResume(error -> {
                    log.error("Order dispatch sweep failed", error);
                    return Mono.just(SweepResult.skipped());
                })
                .defaultIfEmpty(SweepResult.skipped());
    }

    private static void logResult(SweepResult result) {
        if (!result.lockAcquired()) {
            log.debug("Order dispatch sweep skipped: the lease is held by another instance");
        } else if (result.republished() + result.failed() > 0) {
            log.info("Order dispatch sweep: republished={}, failed={}", result.republished(), result.failed());
        } else {
            log.debug("Order dispatch sweep: nothing due");
        }
    }
}
