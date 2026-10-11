package com.ticketing.business.usecase;

import com.ticketing.domain.data.SweepResult;
import reactor.core.publisher.Mono;

public interface RepublishStaleOrdersUseCase {
    Mono<SweepResult> execute();
}
