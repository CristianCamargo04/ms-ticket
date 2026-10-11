package com.ticketing.config.scheduling;

import com.ticketing.domain.data.SweepResult;
import com.ticketing.presentation.OrderDispatchSchedulerController;
import com.ticketing.presentation.ReservationExpirationSchedulerController;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@Slf4j
public final class MaintenanceTimer implements SmartLifecycle {
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 512;

    public static final String RESULTS = "sweeper.results";

    public static final String ORDERS = "sweeper.orders";

    public static final String TYPE_TAG = "type";

    public static final String OUTCOME_TAG = "outcome";

    public static final String RESULT_TAG = "result";

    public static final String RESERVATION_EXPIRATION = "reservation-expiration";

    public static final String ORDER_DISPATCH = "order-dispatch";

    private final ReservationExpirationSchedulerController reservationExpiration;
    private final OrderDispatchSchedulerController orderDispatch;
    private final Duration interval;
    private final Duration sweepTimeout;
    private final MeterRegistry registry;
    private final Scheduler scheduler;
    private final Object lifecycleMonitor = new Object();
    private volatile Disposable subscription;

    public MaintenanceTimer(ReservationExpirationSchedulerController reservationExpiration,
                            OrderDispatchSchedulerController orderDispatch, Duration interval, Duration sweepTimeout,
                            MeterRegistry registry, Scheduler scheduler) {
        this.reservationExpiration = Objects.requireNonNull(reservationExpiration, "reservationExpiration");
        this.orderDispatch = Objects.requireNonNull(orderDispatch, "orderDispatch");
        this.interval = requirePositive(interval, "interval");
        this.sweepTimeout = requirePositive(sweepTimeout, "sweepTimeout");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public boolean isRunning() {
        Disposable current = subscription;
        return current != null && !current.isDisposed();
    }

    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (isRunning()) {
                return;
            }
            log.info("Starting the maintenance timer (every {})", interval);
            subscription = Flux.interval(interval, scheduler)
                    .onBackpressureDrop(tick ->
                            log.debug("Skipping maintenance tick {}: the previous run is still in progress", tick))
                    .concatMap(tick -> runSweeps(), 0)
                    .doOnError(error -> log.error("Maintenance timer failed: sweeps stop until restart", error))
                    .onErrorComplete()
                    .subscribe();
        }
    }

    @Override
    public void stop() {
        synchronized (lifecycleMonitor) {
            Disposable current = subscription;
            if (current != null) {
                current.dispose();
                subscription = null;
                log.info("Maintenance timer stopped");
            }
        }
    }

    private Mono<Void> runSweeps() {
        return sweep(RESERVATION_EXPIRATION, reservationExpiration::execute)
                .then(sweep(ORDER_DISPATCH, orderDispatch::execute));
    }

    private Mono<Void> sweep(String type, Supplier<Mono<SweepResult>> sweeper) {
        return Mono.defer(sweeper)
                .timeout(sweepTimeout, scheduler)
                .defaultIfEmpty(SweepResult.skipped())
                .doOnNext(result -> record(type, result))
                .onErrorResume(error -> {
                    log.error("Sweeper {} failed; the next tick runs it again", type, error);
                    registry.counter(RESULTS, TYPE_TAG, type, OUTCOME_TAG, "failed").increment();
                    return Mono.empty();
                })
                .then();
    }

    private void record(String type, SweepResult result) {
        registry.counter(RESULTS, TYPE_TAG, type, OUTCOME_TAG, result.lockAcquired() ? "completed" : "skipped")
                .increment();
        if (result.lockAcquired()) {
            countOrders(type, "released", result.released());
            countOrders(type, "finalized", result.finalized());
            countOrders(type, "republished", result.republished());
            countOrders(type, "failed", result.failed());
        }
    }

    private void countOrders(String type, String result, int count) {
        registry.counter(ORDERS, TYPE_TAG, type, RESULT_TAG, result).increment(count);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
        return value;
    }
}
