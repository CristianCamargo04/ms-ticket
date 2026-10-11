package com.ticketing.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.presentation.impl.RestControllerTestConfig;
import com.ticketing.presentation.impl.TestJwts;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Authorization rules of the filter chain (against a catch-all probe controller, so only security decides the
 * outcome) and the JWT validation rules of the decoder.
 */
@WebFluxTest(controllers = SecurityConfigTest.ProbeController.class)
@Import({RestControllerTestConfig.class, SecurityConfigTest.ProbeController.class})
class SecurityConfigTest {

    private static final String ISSUER = "https://issuer.example/default";
    private static final String AUDIENCE = "ms-ticket";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    /** Answers 200 on every path and method: the status only reflects the security decision. */
    @RestController
    static class ProbeController {

        @RequestMapping("/**")
        Mono<String> any() {
            return Mono.just("ok");
        }
    }

    private enum Caller {
        ANONYMOUS(client -> client),
        NO_ROLE(client -> client.mutateWith(mockJwt().jwt(jwt -> jwt.subject("user-1")))),
        CUSTOMER(client -> client.mutateWith(TestJwts.customer(TestJwts.CUSTOMER))),
        ADMIN(client -> client.mutateWith(TestJwts.admin(TestJwts.ADMIN)));

        private final UnaryOperator<WebTestClient> authentication;

        Caller(UnaryOperator<WebTestClient> authentication) {
            this.authentication = authentication;
        }
    }

    static Stream<Arguments> authorizationMatrix() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/actuator/health", Caller.ANONYMOUS, 200),
                Arguments.of(HttpMethod.GET, "/actuator/health/readiness", Caller.ANONYMOUS, 200),
                Arguments.of(HttpMethod.GET, "/actuator/info", Caller.ANONYMOUS, 200),
                Arguments.of(HttpMethod.GET, "/actuator/prometheus", Caller.ANONYMOUS, 200),
                Arguments.of(HttpMethod.GET, "/actuator/env", Caller.ANONYMOUS, 401),
                Arguments.of(HttpMethod.GET, "/api/v1/events", Caller.ANONYMOUS, 200),
                Arguments.of(HttpMethod.GET, "/api/v1/events/e-1", Caller.ANONYMOUS, 200),
                Arguments.of(HttpMethod.GET, "/api/v1/events/e-1/availability/stream", Caller.ANONYMOUS, 200),
                Arguments.of(HttpMethod.GET, "/api/v1/events/e-1/tickets/T-1/transitions", Caller.ANONYMOUS, 401),
                Arguments.of(HttpMethod.GET, "/api/v1/events/e-1/tickets/T-1/transitions", Caller.CUSTOMER, 403),
                Arguments.of(HttpMethod.GET, "/api/v1/events/e-1/tickets/T-1/transitions", Caller.ADMIN, 200),
                Arguments.of(HttpMethod.POST, "/api/v1/events", Caller.ANONYMOUS, 401),
                Arguments.of(HttpMethod.POST, "/api/v1/events", Caller.CUSTOMER, 403),
                Arguments.of(HttpMethod.POST, "/api/v1/events", Caller.ADMIN, 200),
                Arguments.of(HttpMethod.POST, "/api/v1/events/e-1/complimentary-tickets", Caller.CUSTOMER, 403),
                Arguments.of(HttpMethod.POST, "/api/v1/events/e-1/complimentary-tickets", Caller.ADMIN, 200),
                Arguments.of(HttpMethod.POST, "/api/v1/events/e-1/orders", Caller.ANONYMOUS, 401),
                Arguments.of(HttpMethod.POST, "/api/v1/events/e-1/orders", Caller.NO_ROLE, 403),
                Arguments.of(HttpMethod.POST, "/api/v1/events/e-1/orders", Caller.CUSTOMER, 200),
                Arguments.of(HttpMethod.POST, "/api/v1/events/e-1/orders", Caller.ADMIN, 200),
                Arguments.of(HttpMethod.GET, "/api/v1/orders/o-1", Caller.ANONYMOUS, 401),
                Arguments.of(HttpMethod.GET, "/api/v1/orders/o-1", Caller.NO_ROLE, 403),
                Arguments.of(HttpMethod.GET, "/api/v1/orders/o-1", Caller.CUSTOMER, 200),
                Arguments.of(HttpMethod.POST, "/api/v1/orders/o-1/confirmation", Caller.CUSTOMER, 200),
                Arguments.of(HttpMethod.DELETE, "/api/v1/orders/o-1/reservation", Caller.CUSTOMER, 200),
                Arguments.of(HttpMethod.DELETE, "/api/v1/orders/o-1/reservation", Caller.NO_ROLE, 403),
                Arguments.of(HttpMethod.PUT, "/api/v1/events/e-1", Caller.ANONYMOUS, 401),
                Arguments.of(HttpMethod.PUT, "/api/v1/events/e-1", Caller.NO_ROLE, 200),
                Arguments.of(HttpMethod.GET, "/internal/anything", Caller.ANONYMOUS, 401),
                Arguments.of(HttpMethod.GET, "/internal/anything", Caller.NO_ROLE, 200));
    }

    @ParameterizedTest(name = "{0} {1} as {2} -> {3}")
    @MethodSource("authorizationMatrix")
    void shouldEnforceTheAuthorizationRules(HttpMethod method, String path, Caller caller, int expectedStatus) {
        caller.authentication.apply(webTestClient)
                .method(method).uri(path)
                .exchange()
                .expectStatus().isEqualTo(expectedStatus);
    }

    @Test
    void shouldStayStatelessWithoutSessionCookies() {
        webTestClient.mutateWith(TestJwts.customer(TestJwts.CUSTOMER)).get().uri("/api/v1/orders/o-1")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);
    }

    @Test
    void shouldNotOfferBasicOrFormLogin() {
        webTestClient.get().uri("/login")
                .header(HttpHeaders.AUTHORIZATION, "Basic YWRtaW46YWRtaW4=")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

    private static TicketingProperties.Security security(String issuer, String audience) {
        Map<String, String> values = new HashMap<>();
        if (issuer != null) {
            values.put("ticketing.security.issuer", issuer);
        }
        if (audience != null) {
            values.put("ticketing.security.audience", audience);
        }
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("ticketing", TicketingProperties.class).security();
    }

    private static Jwt token(String issuer, List<String> audience, String subject, Instant expiresAt) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256")
                .issuer(issuer)
                .audience(audience)
                .issuedAt(expiresAt.minus(Duration.ofHours(1)))
                .expiresAt(expiresAt);
        if (subject != null) {
            builder.subject(subject);
        }
        return builder.build();
    }

    @Test
    void shouldAcceptTokensOfTheIssuerForTheAudience() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfig.jwtValidator(security(ISSUER, AUDIENCE));
        Instant inOneHour = Instant.now().plus(Duration.ofHours(1));

        assertThat(validator.validate(token(ISSUER, List.of(AUDIENCE, "other"), "customer-1", inOneHour))
                .hasErrors()).isFalse();
    }

    @Test
    void shouldRejectTokensOfOtherIssuersAudiencesOrWithoutSubject() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfig.jwtValidator(security(ISSUER, AUDIENCE));
        Instant inOneHour = Instant.now().plus(Duration.ofHours(1));

        assertThat(validator.validate(token("https://evil.example", List.of(AUDIENCE), "c-1", inOneHour))
                .hasErrors()).isTrue();
        assertThat(validator.validate(token(ISSUER, List.of("another-api"), "c-1", inOneHour))
                .hasErrors()).isTrue();
        assertThat(validator.validate(token(ISSUER, List.of(AUDIENCE), null, inOneHour)).hasErrors()).isTrue();
        assertThat(validator.validate(token(ISSUER, List.of(AUDIENCE), " ", inOneHour)).hasErrors()).isTrue();
    }

    @Test
    void shouldRejectExpiredTokens() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfig.jwtValidator(security(ISSUER, AUDIENCE));

        assertThat(validator.validate(token(ISSUER, List.of(AUDIENCE), "c-1",
                Instant.now().minus(Duration.ofMinutes(5)))).hasErrors()).isTrue();
    }

    @Test
    void shouldRequireTheIssuerAndTheAudience() {
        assertThatIllegalArgumentException().isThrownBy(() -> SecurityConfig.jwtValidator(security(null, AUDIENCE)))
                .withMessageContaining("ticketing.security.issuer");
        assertThatIllegalArgumentException().isThrownBy(() -> SecurityConfig.jwtValidator(security(" ", AUDIENCE)));
        assertThatIllegalArgumentException().isThrownBy(() -> SecurityConfig.jwtValidator(security(ISSUER, " ")))
                .withMessageContaining("ticketing.security.audience");
    }

    @Test
    void shouldBuildAJwkSetDecoder() {
        TicketingProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "ticketing.security.issuer", ISSUER))).bindOrCreate("ticketing", TicketingProperties.class);

        assertThat(new SecurityConfig().reactiveJwtDecoder("http://localhost:8090/default/jwks", properties))
                .isInstanceOf(NimbusReactiveJwtDecoder.class);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SecurityConfig().reactiveJwtDecoder(" ", properties));
    }
}
