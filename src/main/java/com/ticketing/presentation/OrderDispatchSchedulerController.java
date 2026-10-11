package com.ticketing.presentation;

import com.ticketing.domain.data.SweepResult;
import reactor.core.publisher.Mono;

public interface OrderDispatchSchedulerController {
    Mono<SweepResult> execute();
}
