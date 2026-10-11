package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.ReleaseExpiredReservationsUseCase;
import com.ticketing.domain.data.SweepResult;
import com.ticketing.presentation.ReservationExpirationSchedulerController;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Timer channel of the expired-reservation sweeper. Never emits an error: a failed run is logged and reported as
 * {@link SweepResult#skipped()}, so the periodic timer keeps running.
 */
@Component
public class ReservationExpirationSchedulerControllerImpl implements ReservationExpirationSchedulerController {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirationSchedulerControllerImpl.class);

    private final ReleaseExpiredReservationsUseCase releaseExpiredReservationsUseCase;

    /**
     * Creates the controller.
     *
     * @param releaseExpiredReservationsUseCase the sweeper use case
     */
    public ReservationExpirationSchedulerControllerImpl(
            ReleaseExpiredReservationsUseCase releaseExpiredReservationsUseCase) {
        this.releaseExpiredReservationsUseCase = Objects.requireNonNull(releaseExpiredReservationsUseCase,
                "releaseExpiredReservationsUseCase");
    }

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
