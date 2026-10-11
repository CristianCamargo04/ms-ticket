package com.ticketing.config.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.cache.impl.EventCacheServiceImpl;
import com.ticketing.cache.impl.EventCacheSettings;
import com.ticketing.config.properties.TicketingProperties;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class CacheConfigTest {

    private final CacheConfig config = new CacheConfig();

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TicketingProperties.class)
    static class PropertiesConfig {
    }

    @Test
    void shouldUseTheDefaultCacheSettings() {
        TicketingProperties defaults = new Binder().bindOrCreate("ticketing", TicketingProperties.class);

        assertThat(config.eventCacheSettings(defaults)).isEqualTo(new EventCacheSettings(Duration.ofSeconds(2), 10000));
    }

    @Test
    void shouldUseTheConfiguredCacheSettings() {
        TicketingProperties custom = new Binder(new MapConfigurationPropertySource(Map.of(
                "ticketing.cache.event-ttl", "500ms",
                "ticketing.cache.event-max-size", "50")))
                .bindOrCreate("ticketing", TicketingProperties.class);

        assertThat(config.eventCacheSettings(custom)).isEqualTo(new EventCacheSettings(Duration.ofMillis(500), 50));
    }

    @Test
    void shouldWireTheEventCacheService() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class, CacheConfig.class)
                .withBean(EventCacheServiceImpl.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EventCacheSettings.class);
                    assertThat(context).hasSingleBean(EventCacheServiceImpl.class);
                });
    }
}
