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

/**
 * Registers the SQS inbound channels and the {@link SqsListenerContainer} that drives them. Only active on worker
 * instances ({@code ticketing.runtime-mode} {@code all} or {@code worker}, see {@link WorkerModeCondition}).
 *
 * <p>The channels are injected through their presentation interfaces: {@code order-reservation} →
 * {@link OrderReservationSqsController}, {@code order-payment} → {@link OrderPaymentSqsController}. Queue names come
 * from {@code aws.sqs.*}; the drain budget of the container is 90 % of
 * {@code spring.lifecycle.timeout-per-shutdown-phase}, the phase it shares with the web server's graceful shutdown.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(WorkerModeCondition.class)
public class SqsListenerConfig {

    /** Graceful shutdown budget of one lifecycle phase. */
    static final String SHUTDOWN_TIMEOUT_PROPERTY = "spring.lifecycle.timeout-per-shutdown-phase";

    /** Spring Boot's default of {@link #SHUTDOWN_TIMEOUT_PROPERTY}. */
    static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Binds the order-reservation queue to its channel.
     *
     * @param awsProperties                AWS settings (queue names)
     * @param orderReservationSqsController the channel
     * @return the listener
     */
    @Bean
    public SqsQueueListener orderReservationQueueListener(AwsProperties awsProperties,
                                                          OrderReservationSqsController orderReservationSqsController) {
        return new SqsQueueListener(awsProperties.sqs().orderReservationQueue(), orderReservationSqsController::handle);
    }

    /**
     * Binds the order-payment queue to its channel.
     *
     * @param awsProperties             AWS settings (queue names)
     * @param orderPaymentSqsController the channel
     * @return the listener
     */
    @Bean
    public SqsQueueListener orderPaymentQueueListener(AwsProperties awsProperties,
                                                      OrderPaymentSqsController orderPaymentSqsController) {
        return new SqsQueueListener(awsProperties.sqs().orderPaymentQueue(), orderPaymentSqsController::handle);
    }

    /**
     * Container settings from {@code ticketing.messaging.*} and the lifecycle phase timeout (the container drains
     * within 90 % of it, see {@link SqsListenerSettings#from}).
     *
     * @param properties  application settings
     * @param environment the environment (shutdown phase timeout)
     * @return the settings
     */
    @Bean
    public SqsListenerSettings sqsListenerSettings(TicketingProperties properties, Environment environment) {
        Duration phaseTimeout = Binder.get(environment)
                .bind(SHUTDOWN_TIMEOUT_PROPERTY, Duration.class)
                .orElse(DEFAULT_SHUTDOWN_TIMEOUT);
        return SqsListenerSettings.from(properties.messaging(), phaseTimeout);
    }

    /**
     * Instrumentation of the container.
     *
     * @param meterRegistry the meter registry (a simple one when metrics are disabled)
     * @return the metrics
     */
    @Bean
    public SqsListenerMetrics sqsListenerMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        return new SqsListenerMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * The container consuming every registered {@link SqsQueueListener}; started and stopped by the context.
     *
     * @param sqsAsyncClient      the asynchronous SQS client
     * @param listeners           the queue bindings
     * @param sqsListenerSettings the settings
     * @param sqsListenerMetrics  the instrumentation
     * @return the container
     */
    @Bean
    public SqsListenerContainer sqsListenerContainer(SqsAsyncClient sqsAsyncClient, List<SqsQueueListener> listeners,
                                                     SqsListenerSettings sqsListenerSettings,
                                                     SqsListenerMetrics sqsListenerMetrics) {
        return new SqsListenerContainer(sqsAsyncClient, listeners, sqsListenerSettings, sqsListenerMetrics,
                Schedulers.parallel());
    }
}
