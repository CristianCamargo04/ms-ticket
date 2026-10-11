package com.ticketing.config.cache;

import com.ticketing.cache.impl.EventCacheSettings;
import com.ticketing.config.properties.TicketingProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CacheConfig {
    @Bean
    public EventCacheSettings eventCacheSettings(TicketingProperties properties) {
        TicketingProperties.Cache cache = properties.cache();
        return new EventCacheSettings(cache.eventTtl(), cache.eventMaxSize());
    }
}
