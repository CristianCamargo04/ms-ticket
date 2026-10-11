package com.ticketing.config.resilience;

import com.ticketing.client.rest.impl.PaymentGatewayChargeClientImpl;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class ResilienceConfig {
    public static final String PAYMENT_GATEWAY = "paymentGateway";

    static final float FAILURE_RATE_THRESHOLD = 50.0f;
    static final int SLIDING_WINDOW_SIZE = 20;
    static final Duration WAIT_DURATION_IN_OPEN_STATE = Duration.ofSeconds(30);
    static final int PERMITTED_CALLS_IN_HALF_OPEN_STATE = 5;

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }

    @Bean
    public TaggedCircuitBreakerMetrics circuitBreakerMetrics(CircuitBreakerRegistry registry) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
    }

    @Bean
    public CircuitBreaker paymentGatewayCircuitBreaker(CircuitBreakerRegistry registry) {
        CircuitBreaker circuitBreaker = registry.circuitBreaker(PAYMENT_GATEWAY, paymentGatewayCircuitBreakerConfig());
        circuitBreaker.getEventPublisher().onStateTransition(event -> log.warn("Circuit breaker {} changed: {}",
                event.getCircuitBreakerName(), event.getStateTransition()));
        return circuitBreaker;
    }

    static CircuitBreakerConfig paymentGatewayCircuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(SLIDING_WINDOW_SIZE)
                .minimumNumberOfCalls(SLIDING_WINDOW_SIZE)
                .failureRateThreshold(FAILURE_RATE_THRESHOLD)
                .waitDurationInOpenState(WAIT_DURATION_IN_OPEN_STATE)
                .permittedNumberOfCallsInHalfOpenState(PERMITTED_CALLS_IN_HALF_OPEN_STATE)
                .recordException(PaymentGatewayChargeClientImpl::isTransientFailure)
                .build();
    }
}
