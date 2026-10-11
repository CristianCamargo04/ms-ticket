package com.ticketing.config.messaging;

import com.ticketing.config.properties.AwsProperties;
import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.presentation.OrderPaymentSqsController;
import com.ticketing.presentation.OrderReservationSqsController;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

@Configuration(proxyBeanMethods = false)
@Conditional(WorkerModeCondition.class)
public class SqsListenerConfig {
    static final String SHUTDOWN_TIMEOUT_PROPERTY = "spring.lifecycle.timeout-per-shutdown-phase";

    static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    public SqsQueueListener orderReservationQueueListener(AwsProperties awsProperties,
                                                          OrderReservationSqsController orderReservationSqsController) {
        return new SqsQueueListener(awsProperties.sqs().orderReservationQueue(), orderReservationSqsController::handle);
    }

    @Bean
    public SqsQueueListener orderPaymentQueueListener(AwsProperties awsProperties,
                                                      OrderPaymentSqsController orderPaymentSqsController) {
        return new SqsQueueListener(awsProperties.sqs().orderPaymentQueue(), orderPaymentSqsController::handle);
    }

    @Bean
    public SqsListenerSettings sqsListenerSettings(TicketingProperties properties, Environment environment) {
        Duration phaseTimeout = Binder.get(environment)
                .bind(SHUTDOWN_TIMEOUT_PROPERTY, Duration.class)
                .orElse(DEFAULT_SHUTDOWN_TIMEOUT);
        return SqsListenerSettings.from(properties.messaging(), phaseTimeout);
    }

    @Bean
    public SqsListenerMetrics sqsListenerMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        return new SqsListenerMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    public SqsListenerContainer sqsListenerContainer(SqsAsyncClient sqsAsyncClient, List<SqsQueueListener> listeners,
                                                     SqsListenerSettings sqsListenerSettings,
                                                     SqsListenerMetrics sqsListenerMetrics) {
        return new SqsListenerContainer(sqsAsyncClient, listeners, sqsListenerSettings, sqsListenerMetrics,
                Schedulers.parallel());
    }
}
