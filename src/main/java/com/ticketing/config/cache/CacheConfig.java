package com.ticketing.config.cache;

import com.ticketing.cache.impl.EventCacheSettings;
import com.ticketing.config.properties.TicketingProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Settings of the in-memory event cache.
 */
@Configuration(proxyBeanMethods = false)
public class CacheConfig {

    /**
     * Event cache TTL and size ({@code ticketing.cache.*}).
     *
     * @param properties application settings
     * @return the event cache settings
     */
    @Bean
    public EventCacheSettings eventCacheSettings(TicketingProperties properties) {
        TicketingProperties.Cache cache = properties.cache();
        return new EventCacheSettings(cache.eventTtl(), cache.eventMaxSize());
    }
}
