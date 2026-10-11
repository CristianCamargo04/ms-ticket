package com.ticketing.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.config.web.CorrelationIdWebFilter;
import com.ticketing.config.web.ProblemDetailResponseWriter;
import com.ticketing.config.web.WorkerModeWebConfig;
import com.ticketing.presentation.impl.error.ProblemDetailFactory;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Production topology of the HTTP servers, on real ports: the actuator runs on its own management server
 * ({@code management.server.port}, 8081 in AWS) and the instance is a worker ({@code ticketing.runtime-mode=worker}).
 *
 * <p>Spring Boot serves the management endpoints from a child context whose HTTP handler collects the
 * {@code WebFilter} beans of the parent context too, so the application's {@code SecurityWebFilterChain} (and the
 * correlation filter) also protect the management server: the health probes and Prometheus stay public there,
 * every other actuator endpoint requires a token, and nothing of the actuator is reachable on the business port.
 */
@SpringBootTest(classes = SecurityConfigManagementPortTest.ManagementPortApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "management.endpoints.web.exposure.include=health,info,prometheus",
            "management.endpoint.health.probes.enabled=true",
            "server.forward-headers-strategy=native",
            "ticketing.runtime-mode=worker",
            "ticketing.security.issuer=https://issuer.example/default",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/jwks"})
class SecurityConfigManagementPortTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    private WebTestClient business;
    private WebTestClient management;

    /** The security, web and actuator beans of the application, on top of Spring Boot's auto-configuration. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableConfigurationProperties(TicketingProperties.class)
    @Import({SecurityConfig.class, JwtRoleConverter.class, SecurityProblemHandler.class, ProblemDetailFactory.class,
            ProblemDetailResponseWriter.class, CorrelationIdWebFilter.class, WorkerModeWebConfig.class})
    static class ManagementPortApplication {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @BeforeEach
    void clients() {
        business = WebTestClient.bindToServer().baseUrl("http://localhost:" + serverPort)
                .responseTimeout(TIMEOUT).build();
        management = WebTestClient.bindToServer().baseUrl("http://localhost:" + managementPort)
                .responseTimeout(TIMEOUT).build();
    }

    @Test
    void shouldServePublicHealthProbesAndPrometheusOnTheManagementPort() {
        assertThat(managementPort).isPositive().isNotEqualTo(serverPort);

        management.get().uri("/actuator/health/readiness").exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Correlation-Id")
                .expectBody().jsonPath("$.status").isEqualTo("UP");
        management.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        management.get().uri("/actuator/health").exchange().expectStatus().isOk();
        management.get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
    }

    @Test
    void shouldStillRequireATokenForTheOtherManagementPaths() {
        management.get().uri("/actuator/env").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

    @Test
    void shouldNotServeTheActuatorOnTheBusinessPort() {
        business.get().uri("/actuator/health/readiness").exchange().expectStatus().isNotFound();
    }

    @Test
    void shouldHideTheApiOfAWorkerOnTheBusinessPort() {
        business.get().uri("/api/v1/events").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.status").isEqualTo(404);
        business.post().uri("/api/v1/events/e-1/orders").exchange().expectStatus().isNotFound();
    }
}
