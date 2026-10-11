package com.ticketing.presentation.impl;

import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.config.security.JwtRoleConverter;
import com.ticketing.config.security.SecurityConfig;
import com.ticketing.config.security.SecurityProblemHandler;
import com.ticketing.config.web.CorrelationIdWebFilter;
import com.ticketing.config.web.ProblemDetailResponseWriter;
import com.ticketing.config.web.WebFluxConfig;
import com.ticketing.presentation.context.impl.RequestContextResolverImpl;
import com.ticketing.presentation.impl.error.GlobalErrorHandler;
import com.ticketing.presentation.impl.error.ProblemDetailFactory;
import com.ticketing.presentation.impl.idempotency.IdempotencyKeyResolver;
import com.ticketing.presentation.impl.idempotency.RequestFingerprintCalculator;
import com.ticketing.presentation.mapper.AvailabilityDtoMapper;
import com.ticketing.presentation.mapper.EventDtoMapper;
import com.ticketing.presentation.mapper.OrderDtoMapper;
import com.ticketing.presentation.mapper.TicketTransitionDtoMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Beans shared by the {@code @WebFluxTest} slices of the REST controllers: the real security chain (with a mocked
 * {@code ReactiveJwtDecoder} declared by each test), error handling, correlation filter, codecs, caller context,
 * idempotency helpers and mappers, plus a fixed clock so problem timestamps are deterministic.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(TicketingProperties.class)
@Import({SecurityConfig.class, JwtRoleConverter.class, SecurityProblemHandler.class, GlobalErrorHandler.class,
        ProblemDetailFactory.class, ProblemDetailResponseWriter.class, CorrelationIdWebFilter.class,
        WebFluxConfig.class, RequestContextResolverImpl.class, IdempotencyKeyResolver.class,
        RequestFingerprintCalculator.class, EventDtoMapper.class, AvailabilityDtoMapper.class, OrderDtoMapper.class,
        TicketTransitionDtoMapper.class})
public class RestControllerTestConfig {

    /** The instant of the fixed test clock. */
    public static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

    /**
     * Fixed clock.
     *
     * @return a clock frozen at {@link #NOW}
     */
    @Bean
    public Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
