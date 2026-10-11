package com.ticketing.config.resilience;

import com.ticketing.client.rest.impl.PaymentGatewayChargeClientImpl;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Circuit breakers of the outbound calls (resilience4j).
 */
@Configuration(proxyBeanMethods = false)
public class ResilienceConfig {

    /** Name of the payment gateway circuit breaker (metrics, logs). */
    public static final String PAYMENT_GATEWAY = "paymentGateway";

    static final float FAILURE_RATE_THRESHOLD = 50.0f;
    static final int SLIDING_WINDOW_SIZE = 20;
    static final Duration WAIT_DURATION_IN_OPEN_STATE = Duration.ofSeconds(30);
    static final int PERMITTED_CALLS_IN_HALF_OPEN_STATE = 5;

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    /**
     * Registry of the application circuit breakers.
     *
     * @return the registry
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }

    /**
     * Circuit breaker of the payment gateway: opens when at least half of the last 20 calls failed, stays open
     * 30 s, then lets 5 trial calls through. Only transient failures (5xx, timeouts, I/O errors) count as failures;
     * a 4xx answer proves the gateway is up and counts as a success. State transitions are logged.
     *
     * @param registry the circuit breaker registry
     * @return the circuit breaker named {@value #PAYMENT_GATEWAY}
     */
    @Bean
    public CircuitBreaker paymentGatewayCircuitBreaker(CircuitBreakerRegistry registry) {
        CircuitBreaker circuitBreaker = registry.circuitBreaker(PAYMENT_GATEWAY, paymentGatewayCircuitBreakerConfig());
        circuitBreaker.getEventPublisher().onStateTransition(event -> log.warn("Circuit breaker {} changed: {}",
                event.getCircuitBreakerName(), event.getStateTransition()));
        return circuitBreaker;
    }

    /**
     * Configuration of the payment gateway circuit breaker.
     *
     * @return the configuration
     */
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
