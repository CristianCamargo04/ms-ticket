package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.ReleaseExpiredReservationsUseCase;
import com.ticketing.domain.data.SweepResult;
import com.ticketing.presentation.ReservationExpirationSchedulerController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationExpirationSchedulerControllerImpl implements ReservationExpirationSchedulerController {

    private final ReleaseExpiredReservationsUseCase releaseExpiredReservationsUseCase;

    @Override
    public Mono<SweepResult> execute() {
        return Mono.defer(releaseExpiredReservationsUseCase::execute)
                .doOnNext(ReservationExpirationSchedulerControllerImpl::logResult)
                .onErrorResume(error -> {
                    log.error("Reservation expiration sweep failed", error);
                    return Mono.just(SweepResult.skipped());
                })
                .defaultIfEmpty(SweepResult.skipped());
    }

    private static void logResult(SweepResult result) {
        if (!result.lockAcquired()) {
            log.debug("Reservation expiration sweep skipped: the lease is held by another instance");
        } else if (result.released() + result.finalized() + result.failed() > 0) {
            log.info("Reservation expiration sweep: released={}, finalized={}, failed={}",
                    result.released(), result.finalized(), result.failed());
        } else {
            log.debug("Reservation expiration sweep: nothing due");
        }
    }
}
