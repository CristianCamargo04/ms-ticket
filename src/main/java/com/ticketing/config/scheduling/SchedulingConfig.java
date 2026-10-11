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

@Configuration(proxyBeanMethods = false)
@Conditional(WorkerModeCondition.class)
public class SchedulingConfig {
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
