package com.ticketing.config.observability;

import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.enums.RuntimeModeEnum;
import com.ticketing.presentation.context.RequestContext;
import io.micrometer.context.ContextRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Locale;
import org.slf4j.MDC;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Hooks;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig implements InitializingBean {
    public static final String APPLICATION_TAG = "application";

    public static final String RUNTIME_MODE_TAG = "runtime_mode";

    static final String DEFAULT_APPLICATION_NAME = "ms-ticket";

    private static final String CORRELATION_ID_KEY = RequestContext.CORRELATION_ID_KEY;

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTagsMeterRegistryCustomizer(Environment environment,
                                                                                    TicketingProperties properties) {
        Tags tags = commonTags(environment.getProperty("spring.application.name", DEFAULT_APPLICATION_NAME),
                properties.runtimeMode());
        return registry -> registry.config().commonTags(tags);
    }

    @Override
    public void afterPropertiesSet() {
        enableContextPropagation();
    }

    static Tags commonTags(String applicationName, RuntimeModeEnum runtimeMode) {
        return Tags.of(APPLICATION_TAG, applicationName, RUNTIME_MODE_TAG, runtimeMode.name().toLowerCase(Locale.ROOT));
    }

    static void enableContextPropagation() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(CORRELATION_ID_KEY,
                () -> MDC.get(CORRELATION_ID_KEY),
                value -> MDC.put(CORRELATION_ID_KEY, value),
                () -> MDC.remove(CORRELATION_ID_KEY));
        if (!Hooks.isAutomaticContextPropagationEnabled()) {
            Hooks.enableAutomaticContextPropagation();
        }
    }
}
