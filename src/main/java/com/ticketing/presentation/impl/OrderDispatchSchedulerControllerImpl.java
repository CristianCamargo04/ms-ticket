package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.RepublishStaleOrdersUseCase;
import com.ticketing.domain.data.SweepResult;
import com.ticketing.presentation.OrderDispatchSchedulerController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDispatchSchedulerControllerImpl implements OrderDispatchSchedulerController {

    private final RepublishStaleOrdersUseCase republishStaleOrdersUseCase;

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
