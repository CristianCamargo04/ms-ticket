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

/**
 * Cross-cutting observability settings.
 *
 * <ul>
 *   <li>Every meter carries the common tags {@code application} ({@code spring.application.name}) and
 *       {@code runtime_mode} ({@code all}, {@code api} or {@code worker}), so API and worker services can be told
 *       apart in dashboards.</li>
 *   <li>Reactor automatic context propagation is enabled once at startup, and the Reactor context key
 *       {@value RequestContext#CORRELATION_ID_KEY} is bridged to the SLF4J MDC key of the same name: every log line
 *       written inside a pipeline whose context holds a correlation id (HTTP requests, SQS messages) carries it,
 *       whatever thread runs the operator.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig implements InitializingBean {

    /** Common tag holding the application name. */
    public static final String APPLICATION_TAG = "application";

    /** Common tag holding the runtime mode. */
    public static final String RUNTIME_MODE_TAG = "runtime_mode";

    /** Application name used when {@code spring.application.name} is not set. */
    static final String DEFAULT_APPLICATION_NAME = "ms-ticket";

    private static final String CORRELATION_ID_KEY = RequestContext.CORRELATION_ID_KEY;

    /**
     * Adds the common tags to every meter registry.
     *
     * @param environment the environment ({@code spring.application.name})
     * @param properties  application settings (runtime mode)
     * @return the customizer
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTagsMeterRegistryCustomizer(Environment environment,
                                                                                    TicketingProperties properties) {
        Tags tags = commonTags(environment.getProperty("spring.application.name", DEFAULT_APPLICATION_NAME),
                properties.runtimeMode());
        return registry -> registry.config().commonTags(tags);
    }

    /**
     * Enables the context propagation when the configuration is initialized.
     */
    @Override
    public void afterPropertiesSet() {
        enableContextPropagation();
    }

    /**
     * Builds the common tags.
     *
     * @param applicationName the application name
     * @param runtimeMode     the runtime mode
     * @return {@code application} and {@code runtime_mode} (lower case)
     */
    static Tags commonTags(String applicationName, RuntimeModeEnum runtimeMode) {
        return Tags.of(APPLICATION_TAG, applicationName, RUNTIME_MODE_TAG, runtimeMode.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Registers the correlation id MDC accessor and enables Reactor automatic context propagation. Idempotent: the
     * accessor replaces a previous registration under the same key and the hook is only installed once.
     */
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
