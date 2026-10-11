package com.ticketing.config.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.presentation.OrderDispatchSchedulerController;
import com.ticketing.presentation.ReservationExpirationSchedulerController;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SchedulingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, SchedulingConfig.class)
            .withBean(ReservationExpirationSchedulerController.class,
                    () -> mock(ReservationExpirationSchedulerController.class))
            .withBean(OrderDispatchSchedulerController.class, () -> mock(OrderDispatchSchedulerController.class));

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TicketingProperties.class)
    static class PropertiesConfig {
    }

    @Test
    void shouldStartTheMaintenanceTimerOnWorkerInstances() {
        runner.withPropertyValues("ticketing.runtime-mode=worker", "ticketing.sweeper.interval=5s")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MaintenanceTimer.class);
                    assertThat(context.getBean(MaintenanceTimer.class).isRunning()).isTrue();
                });
    }

    @Test
    void shouldStartTheMaintenanceTimerByDefault() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(MaintenanceTimer.class));
    }

    @Test
    void shouldNotScheduleSweepsOnApiInstances() {
        runner.withPropertyValues("ticketing.runtime-mode=api")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(MaintenanceTimer.class));
    }

    @Test
    void shouldStopTheTimerWhenTheContextCloses() {
        runner.run(context -> {
            MaintenanceTimer timer = context.getBean(MaintenanceTimer.class);
            context.close();
            assertThat(timer.isRunning()).isFalse();
        });
    }
}
