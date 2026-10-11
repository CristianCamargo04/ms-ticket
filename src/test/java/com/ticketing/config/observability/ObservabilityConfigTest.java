package com.ticketing.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.enums.RuntimeModeEnum;
import io.micrometer.context.ContextRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class ObservabilityConfigTest {

    private final ObservabilityConfig config = new ObservabilityConfig();
    private boolean propagationEnabledBefore;

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TicketingProperties.class)
    static class PropertiesConfig {
    }

    @BeforeEach
    void rememberGlobalState() {
        propagationEnabledBefore = Hooks.isAutomaticContextPropagationEnabled();
    }

    @AfterEach
    void restoreGlobalState() {
        if (!propagationEnabledBefore) {
            Hooks.disableAutomaticContextPropagation();
            ContextRegistry.getInstance().removeThreadLocalAccessor("correlationId");
        }
        MDC.clear();
    }

    @Test
    void shouldTagEveryMeterWithApplicationAndRuntimeMode() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.application.name", "ticket-worker");
        MeterRegistryCustomizer<MeterRegistry> customizer =
                config.commonTagsMeterRegistryCustomizer(environment, properties("worker"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        customizer.customize(registry);

        assertThat(registry.counter("orders.placed").getId().getTags())
                .containsExactlyInAnyOrder(Tag.of("application", "ticket-worker"), Tag.of("runtime_mode", "worker"));
    }

    @Test
    void shouldDefaultTheApplicationTagToTheServiceName() {
        MeterRegistryCustomizer<MeterRegistry> customizer =
                config.commonTagsMeterRegistryCustomizer(new MockEnvironment(), properties("api"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        customizer.customize(registry);

        assertThat(registry.counter("orders.placed").getId().getTag(ObservabilityConfig.APPLICATION_TAG))
                .isEqualTo("ms-ticket");
        assertThat(registry.counter("orders.placed").getId().getTag(ObservabilityConfig.RUNTIME_MODE_TAG))
                .isEqualTo("api");
    }

    @Test
    void shouldBuildLowerCaseCommonTags() {
        assertThat(ObservabilityConfig.commonTags("svc", RuntimeModeEnum.ALL))
                .containsExactlyInAnyOrder(Tag.of("application", "svc"), Tag.of("runtime_mode", "all"));
    }

    @Test
    void shouldPropagateTheCorrelationIdFromTheReactorContextToTheMdcAcrossThreads() {
        config.afterPropertiesSet();
        config.afterPropertiesSet();

        assertThat(Hooks.isAutomaticContextPropagationEnabled()).isTrue();
        StepVerifier.create(Mono.just("value")
                        .publishOn(Schedulers.boundedElastic())
                        .map(value -> String.valueOf(MDC.get("correlationId")))
                        .contextWrite(Context.of("correlationId", "corr-42")))
                .expectNext("corr-42")
                .verifyComplete();
    }

    @Test
    void shouldRegisterTheCustomizerAndEnableContextPropagationInTheContext() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class, ObservabilityConfig.class)
                .withPropertyValues("ticketing.runtime-mode=api")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MeterRegistryCustomizer.class);
                    assertThat(Hooks.isAutomaticContextPropagationEnabled()).isTrue();
                });
    }

    private static TicketingProperties properties(String runtimeMode) {
        return new Binder(new MapConfigurationPropertySource(Map.of("ticketing.runtime-mode", runtimeMode)))
                .bindOrCreate("ticketing", TicketingProperties.class);
    }
}
