package com.ticketing.config.security;

import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.config.web.CorrelationIdWebFilter;
import com.ticketing.config.web.ProblemDetailResponseWriter;
import com.ticketing.config.web.WebFluxConfig;
import com.ticketing.presentation.context.impl.RequestContextResolverImpl;
import com.ticketing.presentation.impl.error.GlobalErrorHandler;
import com.ticketing.presentation.impl.error.ProblemDetailFactory;
import com.ticketing.presentation.impl.idempotency.IdempotencyKeyResolver;
import com.ticketing.presentation.impl.idempotency.RequestFingerprintCalculator;
import com.ticketing.presentation.mapper.OrderDtoMapper;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(TicketingProperties.class)
@Import({SecurityConfig.class, JwtRoleConverter.class, SecurityProblemHandler.class, GlobalErrorHandler.class,
        ProblemDetailFactory.class, ProblemDetailResponseWriter.class, CorrelationIdWebFilter.class,
        WebFluxConfig.class, RequestContextResolverImpl.class, IdempotencyKeyResolver.class,
        RequestFingerprintCalculator.class, OrderDtoMapper.class})
class SecurityIntegrationTestConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
