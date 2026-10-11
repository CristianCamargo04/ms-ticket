package com.ticketing.config.scheduling;

import com.ticketing.config.messaging.WorkerModeCondition;
import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.presentation.OrderDispatchSchedulerController;
import com.ticketing.presentation.ReservationExpirationSchedulerController;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Schedulers;

/**
 * Wires the {@link MaintenanceTimer} that drives both sweeper channels. Only active on worker instances
 * ({@code ticketing.runtime-mode} {@code all} or {@code worker}); Reactor timers are used instead of Spring's
 * {@code @Scheduled}, so no task scheduler thread is ever blocked by a sweep.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(WorkerModeCondition.class)
public class SchedulingConfig {

    /**
     * The sweeper timer, ticking every {@code ticketing.sweeper.interval}; a sweep is bounded by
     * {@code ticketing.sweeper.lease}.
     *
     * @param properties                               application settings
     * @param reservationExpirationSchedulerController the expired-reservation sweeper channel
     * @param orderDispatchSchedulerController         the stale-order dispatch sweeper channel
     * @param meterRegistry                            the meter registry (a simple one when metrics are disabled)
     * @return the timer, started by the application context
     */
    @Bean
    public MaintenanceTimer maintenanceTimer(TicketingProperties properties,
                                             ReservationExpirationSchedulerController reservationExpirationSchedulerController,
                                             OrderDispatchSchedulerController orderDispatchSchedulerController,
                                             ObjectProvider<MeterRegistry> meterRegistry) {
        TicketingProperties.Sweeper sweeper = properties.sweeper();
        return new MaintenanceTimer(reservationExpirationSchedulerController, orderDispatchSchedulerController,
                sweeper.interval(), sweeper.lease(), meterRegistry.getIfAvailable(SimpleMeterRegistry::new),
                Schedulers.parallel());
    }
}
