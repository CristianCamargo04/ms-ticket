package com.ticketing.config.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class ResilienceConfigTest {

    private final ResilienceConfig config = new ResilienceConfig();

    private static WebClientResponseException responseError(int code) {
        return WebClientResponseException.create(HttpStatusCode.valueOf(code), "error", new HttpHeaders(),
                new byte[0], StandardCharsets.UTF_8, null);
    }

    private CircuitBreaker newCircuitBreaker() {
        return config.paymentGatewayCircuitBreaker(config.circuitBreakerRegistry());
    }

    @Test
    void shouldConfigureThePaymentGatewayCircuitBreakerAsSpecified() {
        CircuitBreakerConfig circuitBreakerConfig = ResilienceConfig.paymentGatewayCircuitBreakerConfig();

        assertThat(circuitBreakerConfig.getSlidingWindowType())
                .isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
        assertThat(circuitBreakerConfig.getSlidingWindowSize()).isEqualTo(20);
        assertThat(circuitBreakerConfig.getMinimumNumberOfCalls()).isEqualTo(20);
        assertThat(circuitBreakerConfig.getFailureRateThreshold()).isEqualTo(50.0f);
        assertThat(circuitBreakerConfig.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(30_000L);
        assertThat(circuitBreakerConfig.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(5);
    }

    @Test
    void shouldNameTheCircuitBreakerPaymentGateway() {
        CircuitBreaker circuitBreaker = newCircuitBreaker();

        assertThat(circuitBreaker.getName()).isEqualTo(ResilienceConfig.PAYMENT_GATEWAY).isEqualTo("paymentGateway");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void shouldOpenWhenHalfOfTheWindowAreTransientFailures() {
        CircuitBreaker circuitBreaker = newCircuitBreaker();

        for (int i = 0; i < 10; i++) {
            circuitBreaker.onSuccess(1, TimeUnit.MILLISECONDS);
            circuitBreaker.onError(1, TimeUnit.MILLISECONDS, i % 2 == 0 ? new TimeoutException() : responseError(503));
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuitBreaker.tryAcquirePermission()).isFalse();
    }

    @Test
    void shouldNotOpenOnClientErrorsBecauseTheGatewayAnswered() {
        CircuitBreaker circuitBreaker = newCircuitBreaker();

        for (int i = 0; i < 20; i++) {
            circuitBreaker.onError(1, TimeUnit.MILLISECONDS, responseError(400));
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(20);
    }

    @Test
    void shouldStayClosedBelowTheMinimumNumberOfCalls() {
        CircuitBreaker circuitBreaker = newCircuitBreaker();

        for (int i = 0; i < 19; i++) {
            circuitBreaker.onError(1, TimeUnit.MILLISECONDS, new TimeoutException());
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void shouldLogStateTransitions() {
        CircuitBreaker circuitBreaker = newCircuitBreaker();

        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void shouldRegisterTheRegistryAndTheCircuitBreakerBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(ResilienceConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CircuitBreakerRegistry registry = context.getBean(CircuitBreakerRegistry.class);
                    CircuitBreaker circuitBreaker = context.getBean(CircuitBreaker.class);
                    assertThat(context).getBean("paymentGatewayCircuitBreaker").isSameAs(circuitBreaker);
                    assertThat(registry.find(ResilienceConfig.PAYMENT_GATEWAY)).containsSame(circuitBreaker);
                });
    }

    @Test
    void shouldPublishTheStateAndCallsOfEveryCircuitBreakerAsMeters() {
        CircuitBreakerRegistry registry = config.circuitBreakerRegistry();
        CircuitBreaker circuitBreaker = config.paymentGatewayCircuitBreaker(registry);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        TaggedCircuitBreakerMetrics binder = config.circuitBreakerMetrics(registry);
        binder.bindTo(meters);
        circuitBreaker.onSuccess(1, TimeUnit.MILLISECONDS);

        Gauge closed = meters.get("resilience4j.circuitbreaker.state")
                .tag("name", ResilienceConfig.PAYMENT_GATEWAY).tag("state", "closed").gauge();
        assertThat(closed.value()).isEqualTo(1.0);
        assertThat(meters.find("resilience4j.circuitbreaker.calls")
                .tag("name", ResilienceConfig.PAYMENT_GATEWAY).tag("kind", "successful").meters()).isNotEmpty();
        assertThat(meters.find("resilience4j.circuitbreaker.failure.rate")
                .tag("name", ResilienceConfig.PAYMENT_GATEWAY).gauges()).isNotEmpty();

        circuitBreaker.transitionToOpenState();
        assertThat(closed.value()).isZero();
        assertThat(meters.get("resilience4j.circuitbreaker.state")
                .tag("name", ResilienceConfig.PAYMENT_GATEWAY).tag("state", "open").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void shouldLetSpringBootBindTheCircuitBreakerMetricsToTheMeterRegistry() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class, SimpleMetricsExportAutoConfiguration.class))
                .withUserConfiguration(ResilienceConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(TaggedCircuitBreakerMetrics.class);
                    MeterRegistry meters = context.getBean(MeterRegistry.class);
                    assertThat(meters.find("resilience4j.circuitbreaker.state")
                            .tag("name", ResilienceConfig.PAYMENT_GATEWAY).gauges()).isNotEmpty();
                });
    }
}
