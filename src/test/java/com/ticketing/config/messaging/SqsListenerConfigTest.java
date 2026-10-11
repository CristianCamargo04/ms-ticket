package com.ticketing.config.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.config.properties.AwsProperties;
import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.presentation.OrderPaymentSqsController;
import com.ticketing.presentation.OrderReservationSqsController;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

class SqsListenerConfigTest {

    private final OrderReservationSqsController reservationController = mock(OrderReservationSqsController.class);
    private final OrderPaymentSqsController paymentController = mock(OrderPaymentSqsController.class);

    /** Every SQS call stays pending, like a queue that is not reachable yet. */
    private final SqsAsyncClient sqsAsyncClient =
            mock(SqsAsyncClient.class, invocation -> new CompletableFuture<>());

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, SqsListenerConfig.class)
            .withBean(SqsAsyncClient.class, () -> sqsAsyncClient)
            .withBean(OrderReservationSqsController.class, () -> reservationController)
            .withBean(OrderPaymentSqsController.class, () -> paymentController);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({TicketingProperties.class, AwsProperties.class})
    static class PropertiesConfig {
    }

    @Test
    void shouldRegisterBothQueuesAndStartTheContainerInWorkerMode() {
        when(reservationController.handle("reservation-payload")).thenReturn(Mono.empty());
        when(paymentController.handle("payment-payload")).thenReturn(Mono.empty());

        runner.withPropertyValues("ticketing.runtime-mode=worker").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SqsListenerContainer.class);
            assertThat(context.getBean(SqsListenerContainer.class).isRunning()).isTrue();
            Map<String, SqsQueueListener> listeners = context.getBeansOfType(SqsQueueListener.class);
            assertThat(listeners).hasSize(2);
            SqsQueueListener reservation = listeners.get("orderReservationQueueListener");
            SqsQueueListener payment = listeners.get("orderPaymentQueueListener");
            assertThat(reservation.queueName()).isEqualTo("ticketing-order-reservation");
            assertThat(payment.queueName()).isEqualTo("ticketing-order-payment");
            StepVerifier.create(reservation.handler().apply("reservation-payload")).verifyComplete();
            StepVerifier.create(payment.handler().apply("payment-payload")).verifyComplete();
        });

        verify(reservationController).handle("reservation-payload");
        verify(paymentController).handle("payment-payload");
    }

    @Test
    void shouldRunTheWorkersByDefault() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(SqsListenerContainer.class)
                .hasSingleBean(SqsListenerSettings.class)
                .hasSingleBean(SqsListenerMetrics.class));
    }

    @Test
    void shouldNotConsumeQueuesOnApiInstances() {
        runner.withPropertyValues("ticketing.runtime-mode=api").run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(SqsListenerContainer.class)
                .doesNotHaveBean(SqsQueueListener.class)
                .doesNotHaveBean(SqsListenerSettings.class));
    }

    @Test
    void shouldStopTheContainerWhenTheContextCloses() {
        runner.run(context -> {
            SqsListenerContainer container = context.getBean(SqsListenerContainer.class);
            context.close();
            assertThat(container.isRunning()).isFalse();
        });
    }

    @Test
    void shouldBuildSettingsFromMessagingPropertiesAndTheShutdownTimeout() {
        runner.withPropertyValues("ticketing.messaging.pollers=3", "ticketing.messaging.concurrency=8",
                        "aws.sqs.order-payment-queue=payments", "spring.lifecycle.timeout-per-shutdown-phase=45s")
                .run(context -> {
                    SqsListenerSettings settings = context.getBean(SqsListenerSettings.class);
                    assertThat(settings.pollers()).isEqualTo(3);
                    assertThat(settings.concurrency()).isEqualTo(8);
                    assertThat(settings.shutdownTimeout()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(context.getBean("orderPaymentQueueListener", SqsQueueListener.class).queueName())
                            .isEqualTo("payments");
                });
    }

    @Test
    void shouldDefaultTheShutdownTimeoutToThirtySeconds() {
        runner.run(context -> assertThat(context.getBean(SqsListenerSettings.class).shutdownTimeout())
                .isEqualTo(SqsListenerConfig.DEFAULT_SHUTDOWN_TIMEOUT)
                .isEqualTo(Duration.ofSeconds(30)));
    }

    @Test
    void shouldRecordMetricsInTheApplicationMeterRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        runner.withBean(MeterRegistry.class, () -> registry).run(context -> {
            context.getBean(SqsListenerMetrics.class).inFlight("orders");
            assertThat(registry.find(SqsListenerMetrics.IN_FLIGHT).tag("queue", "orders").gauge()).isNotNull();
        });
    }

    @Test
    void shouldFallBackToASimpleRegistryWithoutMetricsSupport() {
        runner.run(context -> assertThat(context.getBean(SqsListenerMetrics.class).inFlight("orders")).hasValue(0));
    }
}
