package com.ticketing.config.ratelimit;

import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.config.web.ProblemDetailResponseWriter;
import com.ticketing.presentation.impl.error.ProblemDetailFactory;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "ticketing.rate-limit.enabled", matchIfMissing = true)
public class RateLimitConfig {
    @Bean
    public RateLimitWebFilter rateLimitWebFilter(TicketingProperties properties, Clock clock,
                                                 ProblemDetailFactory problems, ProblemDetailResponseWriter writer) {
        return new RateLimitWebFilter(properties.rateLimit(), clock, problems, writer);
    }
}
