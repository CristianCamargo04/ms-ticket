package com.ticketing.config.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ticketing.domain.data.SweepResult;
import com.ticketing.presentation.OrderDispatchSchedulerController;
import com.ticketing.presentation.ReservationExpirationSchedulerController;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

@ExtendWith(MockitoExtension.class)
class MaintenanceTimerTest {

    private static final Duration INTERVAL = Duration.ofSeconds(15);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final SweepResult EXPIRATION_RESULT = new SweepResult(true, 3, 1, 0, 1);
    private static final SweepResult DISPATCH_RESULT = new SweepResult(true, 0, 0, 2, 0);

    @Mock
    private ReservationExpirationSchedulerController reservationExpiration;

    @Mock
    private OrderDispatchSchedulerController orderDispatch;

    private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger timerLogger = (Logger) LoggerFactory.getLogger(MaintenanceTimer.class);
    private MaintenanceTimer timer;

    @BeforeEach
    void setUp() {
        logs.start();
        timerLogger.addAppender(logs);
        timerLogger.setLevel(Level.DEBUG);
        timer = new MaintenanceTimer(reservationExpiration, orderDispatch, INTERVAL, LEASE, registry, scheduler);
    }

    @AfterEach
    void tearDown() {
        timer.stop();
        scheduler.dispose();
        timerLogger.detachAppender(logs);
        timerLogger.setLevel(null);
    }

    @Test
    void shouldRunBothSweepersInOrderOnEveryTick() {
        when(reservationExpiration.execute()).thenReturn(Mono.just(EXPIRATION_RESULT));
        when(orderDispatch.execute()).thenReturn(Mono.just(DISPATCH_RESULT));

        timer.start();
        scheduler.advanceTimeBy(INTERVAL.minusMillis(1));
        verify(reservationExpiration, never()).execute();

        scheduler.advanceTimeBy(Duration.ofMillis(1));
        InOrder firstTick = inOrder(reservationExpiration, orderDispatch);
        firstTick.verify(reservationExpiration).execute();
        firstTick.verify(orderDispatch).execute();

        scheduler.advanceTimeBy(INTERVAL);
        verify(reservationExpiration, times(2)).execute();
        verify(orderDispatch, times(2)).execute();
        assertThat(count(MaintenanceTimer.RESULTS, MaintenanceTimer.RESERVATION_EXPIRATION, "outcome", "completed"))
                .isEqualTo(2.0);
        assertThat(count(MaintenanceTimer.RESULTS, MaintenanceTimer.ORDER_DISPATCH, "outcome", "completed"))
                .isEqualTo(2.0);
        assertThat(count(MaintenanceTimer.ORDERS, MaintenanceTimer.RESERVATION_EXPIRATION, "result", "released"))
                .isEqualTo(6.0);
        assertThat(count(MaintenanceTimer.ORDERS, MaintenanceTimer.RESERVATION_EXPIRATION, "result", "finalized"))
                .isEqualTo(2.0);
        assertThat(count(MaintenanceTimer.ORDERS, MaintenanceTimer.RESERVATION_EXPIRATION, "result", "failed"))
                .isEqualTo(2.0);
        assertThat(count(MaintenanceTimer.ORDERS, MaintenanceTimer.ORDER_DISPATCH, "result", "republished"))
                .isEqualTo(4.0);
    }

    @Test
    void shouldStartTheSecondSweepOnlyAfterTheFirstCompletes() {
        AtomicBoolean firstCompleted = new AtomicBoolean();
        when(reservationExpiration.execute()).thenReturn(Mono.delay(Duration.ofSeconds(5), scheduler)
                .doOnNext(tick -> firstCompleted.set(true))
                .thenReturn(EXPIRATION_RESULT));
        when(orderDispatch.execute()).thenAnswer(invocation -> {
            assertThat(firstCompleted).isTrue();
            return Mono.just(DISPATCH_RESULT);
        });

        timer.start();
        scheduler.advanceTimeBy(INTERVAL.plusSeconds(4));
        verify(orderDispatch, never()).execute();

        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        verify(orderDispatch).execute();
    }

    @Test
    void shouldKeepTickingWhenASweepFails() {
        when(reservationExpiration.execute())
                .thenReturn(Mono.error(new IllegalStateException("dynamodb down")))
                .thenThrow(new IllegalStateException("synchronous failure"))
                .thenReturn(Mono.just(EXPIRATION_RESULT));
        when(orderDispatch.execute()).thenReturn(Mono.just(DISPATCH_RESULT));

        timer.start();
        scheduler.advanceTimeBy(INTERVAL.multipliedBy(3));

        verify(reservationExpiration, times(3)).execute();
        verify(orderDispatch, times(3)).execute();
        assertThat(timer.isRunning()).isTrue();
        assertThat(count(MaintenanceTimer.RESULTS, MaintenanceTimer.RESERVATION_EXPIRATION, "outcome", "failed"))
                .isEqualTo(2.0);
        assertThat(count(MaintenanceTimer.RESULTS, MaintenanceTimer.RESERVATION_EXPIRATION, "outcome", "completed"))
                .isEqualTo(1.0);
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.ERROR).hasSize(2)
                .allSatisfy(event -> assertThat(event.getFormattedMessage())
                        .isEqualTo("Sweeper reservation-expiration failed; the next tick runs it again"));
    }

    @Test
    void shouldCountRunsSkippedBecauseAnotherInstanceHoldsTheLease() {
        when(reservationExpiration.execute()).thenReturn(Mono.just(SweepResult.skipped()));
        when(orderDispatch.execute()).thenReturn(Mono.empty());

        timer.start();
        scheduler.advanceTimeBy(INTERVAL);

        assertThat(count(MaintenanceTimer.RESULTS, MaintenanceTimer.RESERVATION_EXPIRATION, "outcome", "skipped"))
                .isEqualTo(1.0);
        assertThat(count(MaintenanceTimer.RESULTS, MaintenanceTimer.ORDER_DISPATCH, "outcome", "skipped"))
                .isEqualTo(1.0);
        assertThat(registry.find(MaintenanceTimer.ORDERS).counters()).isEmpty();
    }

    @Test
    void shouldAbortASweepThatOutlivesItsLease() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(reservationExpiration.execute())
                .thenReturn(Mono.<SweepResult>never().doOnCancel(() -> cancelled.set(true)));
        when(orderDispatch.execute()).thenReturn(Mono.just(DISPATCH_RESULT));

        timer.start();
        scheduler.advanceTimeBy(INTERVAL.plus(LEASE).minusMillis(1));
        verify(orderDispatch, never()).execute();

        scheduler.advanceTimeBy(Duration.ofMillis(1));
        assertThat(cancelled).isTrue();
        verify(orderDispatch).execute();
        assertThat(count(MaintenanceTimer.RESULTS, MaintenanceTimer.RESERVATION_EXPIRATION, "outcome", "failed"))
                .isEqualTo(1.0);
    }

    @Test
    void shouldDropTicksWhileARunIsInProgress() {
        when(reservationExpiration.execute())
                .thenReturn(Mono.delay(Duration.ofSeconds(40), scheduler).thenReturn(EXPIRATION_RESULT));
        when(orderDispatch.execute()).thenReturn(Mono.just(DISPATCH_RESULT));
        timer = new MaintenanceTimer(reservationExpiration, orderDispatch, INTERVAL, Duration.ofMinutes(1), registry,
                scheduler);

        // first run from 15s to 55s: the ticks of 30s and 45s are dropped, the next run starts at 60s
        timer.start();
        scheduler.advanceTimeBy(Duration.ofSeconds(59));
        verify(reservationExpiration, times(1)).execute();
        verify(orderDispatch, times(1)).execute();
        assertThat(logs.list).filteredOn(event -> event.getFormattedMessage().startsWith("Skipping maintenance tick"))
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Skipping maintenance tick 1: the previous run is still in progress",
                        "Skipping maintenance tick 2: the previous run is still in progress");

        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        verify(reservationExpiration, times(2)).execute();
    }

    @Test
    void shouldDisposeTheTimerOnStop() {
        timer.start();
        assertThat(timer.isRunning()).isTrue();

        timer.stop();
        timer.stop();
        scheduler.advanceTimeBy(INTERVAL.multipliedBy(4));

        assertThat(timer.isRunning()).isFalse();
        verify(reservationExpiration, never()).execute();
        verify(orderDispatch, never()).execute();
    }

    @Test
    void shouldIgnoreASecondStartAndRestartAfterStop() {
        when(reservationExpiration.execute()).thenReturn(Mono.just(EXPIRATION_RESULT));
        when(orderDispatch.execute()).thenReturn(Mono.just(DISPATCH_RESULT));

        timer.start();
        timer.start();
        scheduler.advanceTimeBy(INTERVAL);
        verify(reservationExpiration, times(1)).execute();

        timer.stop();
        timer.start();
        scheduler.advanceTimeBy(INTERVAL);
        verify(reservationExpiration, times(2)).execute();
    }

    @Test
    void shouldLogWhenTheTimerItselfFails() {
        MeterRegistry failingRegistry = mock(MeterRegistry.class);
        when(failingRegistry.counter(eq(MaintenanceTimer.RESULTS), any(String[].class)))
                .thenThrow(new IllegalStateException("registry down"));
        when(reservationExpiration.execute()).thenReturn(Mono.just(EXPIRATION_RESULT));
        timer = new MaintenanceTimer(reservationExpiration, orderDispatch, INTERVAL, LEASE, failingRegistry,
                scheduler);

        timer.start();
        scheduler.advanceTimeBy(INTERVAL);

        assertThat(timer.isRunning()).isFalse();
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .isEqualTo("Maintenance timer failed: sweeps stop until restart"));
    }

    @Test
    void shouldStartLateAndStopEarlyInTheLifecycle() {
        assertThat(timer.getPhase()).isEqualTo(MaintenanceTimer.PHASE).isLessThan(SmartLifecycle.DEFAULT_PHASE);
        assertThat(timer.isAutoStartup()).isTrue();
        assertThat(timer.isRunning()).isFalse();
    }

    @Test
    void shouldValidateConstructorArguments() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MaintenanceTimer(reservationExpiration,
                orderDispatch, Duration.ZERO, LEASE, registry, scheduler)).withMessageContaining("interval");
        assertThatIllegalArgumentException().isThrownBy(() -> new MaintenanceTimer(reservationExpiration,
                orderDispatch, INTERVAL, LEASE.negated(), registry, scheduler)).withMessageContaining("sweepTimeout");
        assertThatNullPointerException().isThrownBy(() -> new MaintenanceTimer(null, orderDispatch, INTERVAL, LEASE,
                registry, scheduler));
        assertThatNullPointerException().isThrownBy(() -> new MaintenanceTimer(reservationExpiration, null,
                INTERVAL, LEASE, registry, scheduler));
        assertThatNullPointerException().isThrownBy(() -> new MaintenanceTimer(reservationExpiration,
                orderDispatch, null, LEASE, registry, scheduler));
        assertThatNullPointerException().isThrownBy(() -> new MaintenanceTimer(reservationExpiration,
                orderDispatch, INTERVAL, LEASE, null, scheduler));
        assertThatNullPointerException().isThrownBy(() -> new MaintenanceTimer(reservationExpiration,
                orderDispatch, INTERVAL, LEASE, registry, null));
    }

    private double count(String name, String type, String tag, String value) {
        Counter counter = registry.find(name).tags(MaintenanceTimer.TYPE_TAG, type, tag, value).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
