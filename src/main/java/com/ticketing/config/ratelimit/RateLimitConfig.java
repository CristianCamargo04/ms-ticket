package com.ticketing.config.ratelimit;

import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.config.web.ProblemDetailResponseWriter;
import com.ticketing.presentation.impl.error.ProblemDetailFactory;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the API rate limiter unless {@code ticketing.rate-limit.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "ticketing.rate-limit.enabled", matchIfMissing = true)
public class RateLimitConfig {

    /**
     * The rate-limiting web filter.
     *
     * @param properties application settings ({@code ticketing.rate-limit.*})
     * @param clock      time source of the token buckets
     * @param problems   builds the 429 problem
     * @param writer     writes it to the response
     * @return the filter
     */
    @Bean
    public RateLimitWebFilter rateLimitWebFilter(TicketingProperties properties, Clock clock,
                                                 ProblemDetailFactory problems, ProblemDetailResponseWriter writer) {
        return new RateLimitWebFilter(properties.rateLimit(), clock, problems, writer);
    }
}
