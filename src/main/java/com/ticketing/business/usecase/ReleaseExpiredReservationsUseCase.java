package com.ticketing.business.usecase;

import com.ticketing.domain.data.SweepResult;
import reactor.core.publisher.Mono;

public interface ReleaseExpiredReservationsUseCase {
    Mono<SweepResult> execute();
}
