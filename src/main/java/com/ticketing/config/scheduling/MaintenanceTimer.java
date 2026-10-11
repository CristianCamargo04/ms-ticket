package com.ticketing.config.scheduling;

import com.ticketing.domain.data.SweepResult;
import com.ticketing.presentation.OrderDispatchSchedulerController;
import com.ticketing.presentation.ReservationExpirationSchedulerController;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Timer channel of the sweepers: every {@code ticketing.sweeper.interval} it runs the expired-reservation sweeper
 * and then the stale-order dispatch sweeper, sequentially.
 *
 * <p>Pipeline: {@code Flux.interval(interval)} → {@code onBackpressureDrop} → {@code concatMap(runSweeps, 0)}. A tick
 * that fires while the previous run is still in progress is dropped (sweeps never overlap or queue up on one
 * instance; the distributed lease prevents overlaps across instances). Each sweep is bounded by the lease duration
 * — a sweep must not outlive the lease it holds — and its errors are contained: they are logged, counted and the
 * next tick simply runs it again, so the timer never dies. {@link #stop()} disposes the timer; a sweep cancelled
 * mid-run releases its lease reactively and leaves every order consistent (each order transition is one conditional
 * DynamoDB write).
 *
 * <p>Metrics: {@code sweeper.results{type, outcome=completed|skipped|failed}} counts runs ({@code skipped}: another
 * instance held the lease) and {@code sweeper.orders{type, result=released|finalized|republished|failed}} counts
 * the orders handled by completed runs.
 */
public final class MaintenanceTimer implements SmartLifecycle {

    /** Lifecycle phase, shared with the SQS listener container: started last, stopped first. */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 512;

    /** Counter of sweeper runs. */
    public static final String RESULTS = "sweeper.results";

    /** Counter of the orders handled by sweeper runs. */
    public static final String ORDERS = "sweeper.orders";

    /** Tag holding the sweeper type. */
    public static final String TYPE_TAG = "type";

    /** Tag holding the outcome of a run. */
    public static final String OUTCOME_TAG = "outcome";

    /** Tag holding what happened to the orders of a run. */
    public static final String RESULT_TAG = "result";

    /** Sweeper type of the expired reservations (and payment reconciliation). */
    public static final String RESERVATION_EXPIRATION = "reservation-expiration";

    /** Sweeper type of the stale queued orders. */
    public static final String ORDER_DISPATCH = "order-dispatch";

    private static final Logger log = LoggerFactory.getLogger(MaintenanceTimer.class);

    private final ReservationExpirationSchedulerController reservationExpiration;
    private final OrderDispatchSchedulerController orderDispatch;
    private final Duration interval;
    private final Duration sweepTimeout;
    private final MeterRegistry registry;
    private final Scheduler scheduler;
    private final Object lifecycleMonitor = new Object();
    private volatile Disposable subscription;

    /**
     * Creates a stopped timer.
     *
     * @param reservationExpiration the expired-reservation sweeper channel
     * @param orderDispatch         the stale-order dispatch sweeper channel
     * @param interval              delay between two ticks
     * @param sweepTimeout          maximum duration of one sweep (the sweeper lease)
     * @param registry              the meter registry
     * @param scheduler             the scheduler of the ticks and timeouts ({@code Schedulers.parallel()})
     */
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

    /**
     * Starts the timer; does nothing when already running. The first run happens one interval after the start.
     */
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

    /**
     * Disposes the timer, cancelling a sweep in progress.
     */
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
