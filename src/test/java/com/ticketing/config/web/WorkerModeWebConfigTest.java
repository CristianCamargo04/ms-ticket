package com.ticketing.config.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.config.messaging.SqsListenerConfig;
import com.ticketing.config.messaging.SqsListenerContainer;
import com.ticketing.config.scheduling.MaintenanceTimer;
import com.ticketing.config.scheduling.SchedulingConfig;
import com.ticketing.presentation.impl.RestControllerTestConfig;
import com.ticketing.presentation.impl.TestJwts;
import com.ticketing.presentation.impl.error.ProblemDetailFactory;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runtime-mode gating of the HTTP API (ADR-008): a worker-only instance answers {@code 404} on {@code /api/**}
 * before Spring Security runs, while the rest of the server (actuator) keeps working; {@code api} and {@code all}
 * instances do not register the filter.
 */
@WebFluxTest(controllers = WorkerModeWebConfigTest.ProbeController.class,
        properties = "ticketing.runtime-mode=worker")
@Import({RestControllerTestConfig.class, WorkerModeWebConfig.class, WorkerModeWebConfigTest.ProbeController.class})
class WorkerModeWebConfigTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    /** Answers 200 on every path: any other status comes from the filters. */
    @RestController
    static class ProbeController {

        @RequestMapping("/**")
        Mono<String> any() {
            return Mono.just("ok");
        }
    }

    private static ApplicationContextRunner contextRunner() {
        Clock clock = Clock.systemUTC();
        return new ApplicationContextRunner()
                .withUserConfiguration(WorkerModeWebConfig.class)
                .withBean(ProblemDetailFactory.class, () -> new ProblemDetailFactory(clock))
                .withBean(ProblemDetailResponseWriter.class,
                        () -> new ProblemDetailResponseWriter(JsonMapper.builder().build()));
    }

    @Test
    void shouldHideTheApiFromAnonymousAndAuthenticatedCallersAlike() {
        webTestClient.post().uri("/api/v1/events/e-1/orders")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Correlation-Id")
                .expectBody().jsonPath("$.status").isEqualTo(404);

        webTestClient.mutateWith(TestJwts.admin(TestJwts.ADMIN)).get().uri("/api/v1/events")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void shouldKeepServingNonApiPathsThroughTheSecurityChain() {
        webTestClient.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
        webTestClient.get().uri("/internal/anything").exchange().expectStatus().isUnauthorized();
    }

    @ParameterizedTest(name = "ticketing.runtime-mode={0} -> filter registered: {1}")
    @CsvSource({"worker, true", "WORKER, true", "Worker, true", "api, false", "API, false", "all, false"})
    void shouldRegisterTheFilterOnlyOnWorkerInstances(String runtimeMode, boolean registered) {
        contextRunner()
                .withPropertyValues("ticketing.runtime-mode=" + runtimeMode)
                .run(context -> assertThat(context.getBeansOfType(WorkerModeApiWebFilter.class)).hasSize(
                        registered ? 1 : 0));
    }

    @Test
    void shouldServeTheApiWhenNoModeIsConfigured() {
        contextRunner().run(context -> assertThat(context).doesNotHaveBean(WorkerModeApiWebFilter.class));
    }

    @Test
    void shouldStartNeitherQueueConsumersNorSweepersOnApiInstances() {
        // The worker channels are gated by WorkerModeCondition (see SqsListenerConfigTest / SchedulingConfigTest);
        // an API instance must not even define them.
        contextRunner()
                .withUserConfiguration(SqsListenerConfig.class, SchedulingConfig.class)
                .withPropertyValues("ticketing.runtime-mode=api")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SqsListenerContainer.class);
                    assertThat(context).doesNotHaveBean(MaintenanceTimer.class);
                    assertThat(context).doesNotHaveBean(WorkerModeApiWebFilter.class);
                });
    }
}
