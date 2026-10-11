package com.ticketing.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.presentation.impl.RestControllerTestConfig;
import com.ticketing.presentation.impl.TestJwts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
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
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

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
    void shouldAcceptCognitoAccessTokensCarryingTheAudienceAsClientId() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfig.jwtValidator(security(ISSUER, AUDIENCE));
        Instant inOneHour = Instant.now().plus(Duration.ofHours(1));
        Jwt cognitoAccessToken = Jwt.withTokenValue("token").header("alg", "RS256")
                .issuer(ISSUER)
                .subject("customer-1")
                .claim("client_id", AUDIENCE)
                .claim("cognito:groups", List.of("CUSTOMER"))
                .issuedAt(inOneHour.minus(Duration.ofHours(1)))
                .expiresAt(inOneHour)
                .build();
        Jwt foreignClientToken = Jwt.withTokenValue("token").header("alg", "RS256")
                .issuer(ISSUER)
                .subject("customer-1")
                .claim("client_id", "another-app-client")
                .issuedAt(inOneHour.minus(Duration.ofHours(1)))
                .expiresAt(inOneHour)
                .build();

        assertThat(validator.validate(cognitoAccessToken).hasErrors()).isFalse();
        assertThat(validator.validate(foreignClientToken).hasErrors()).isTrue();
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

    private static TicketingProperties properties() {
        return new Binder(new MapConfigurationPropertySource(Map.of("ticketing.security.issuer", ISSUER)))
                .bindOrCreate("ticketing", TicketingProperties.class);
    }

    @Test
    void shouldBuildAJwkSetDecoder() {
        TicketingProperties properties = properties();
        Clock clock = Clock.systemUTC();

        assertThat(new SecurityConfig().reactiveJwtDecoder("http://localhost:8090/default/jwks", properties, clock))
                .isInstanceOf(NimbusReactiveJwtDecoder.class);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SecurityConfig().reactiveJwtDecoder(" ", properties, clock));
    }

    private static String signedToken(RSAKey key, String keyId, String issuer) throws JOSEException {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(),
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .audience(AUDIENCE)
                        .subject("customer-1")
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                        .build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static MockResponse jwkSetResponse(RSAKey key) {
        return new MockResponse.Builder()
                .addHeader(HttpHeaders.CONTENT_TYPE, "application/jwk-set+json")
                .body(new JWKSet(key).toPublicJWKSet().toString())
                .build();
    }

    @Test
    void shouldVerifyTokensWithTheJwkSetAndNotDownloadItAgainForForgedKeyIds() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        RSAKey attackerKey = new RSAKeyGenerator(2048).keyID("forged").generate();
        try (MockWebServer issuer = new MockWebServer()) {
            issuer.enqueue(jwkSetResponse(key));
            issuer.start();
            ReactiveJwtDecoder decoder = new SecurityConfig().reactiveJwtDecoder(
                    issuer.url("/jwks").toString(), properties(), Clock.systemUTC());

            StepVerifier.create(decoder.decode(signedToken(key, "k1", ISSUER)))
                    .assertNext(jwt -> assertThat(jwt.getSubject()).isEqualTo("customer-1"))
                    .verifyComplete();
            for (int i = 0; i < 20; i++) {
                StepVerifier.create(decoder.decode(signedToken(attackerKey, "forged-" + i, ISSUER)))
                        .verifyError(BadJwtException.class);
            }
            StepVerifier.create(decoder.decode(signedToken(key, "k1", "https://evil.example")))
                    .verifyError(JwtValidationException.class);

            assertThat(issuer.getRequestCount()).isEqualTo(1);
            assertThat(issuer.takeRequest().getHeaders().get(HttpHeaders.ACCEPT))
                    .contains("application/jwk-set+json");
        }
    }

    @Test
    void shouldGiveUpOnASlowJwkSetEndpoint() throws Exception {
        try (MockWebServer issuer = new MockWebServer()) {
            issuer.enqueue(new MockResponse.Builder().headersDelay(5, TimeUnit.SECONDS).body("{}").build());
            issuer.start();

            StepVerifier.create(SecurityConfig.jwkSetDocument(issuer.url("/jwks").toString(),
                            Duration.ofSeconds(1), Duration.ofMillis(200)))
                    .expectError()
                    .verify(Duration.ofSeconds(3));
        }
    }

    @Test
    void shouldWriteHstsOnlyForHttpsRequestsIncludingTlsTerminatedByTheLoadBalancer() {
        webTestClient.get().uri("/api/v1/events")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist(StrictTransportSecurityHeader.NAME);

        webTestClient.get().uri("/api/v1/events")
                .header(SecurityConfig.FORWARDED_PROTO_HEADER, "http")
                .exchange()
                .expectHeader().doesNotExist(StrictTransportSecurityHeader.NAME);

        webTestClient.get().uri("/api/v1/events")
                .header(SecurityConfig.FORWARDED_PROTO_HEADER, "https")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(StrictTransportSecurityHeader.NAME, StrictTransportSecurityHeader.VALUE);

        webTestClient.get().uri("/api/v1/events")
                .header(SecurityConfig.FORWARDED_PROTO_HEADER, "HTTPS, http")
                .exchange()
                .expectHeader().valueEquals(StrictTransportSecurityHeader.NAME, StrictTransportSecurityHeader.VALUE);

        webTestClient.mutate().baseUrl("https://localhost").build()
                .get().uri("/api/v1/events")
                .exchange()
                .expectHeader().valueEquals(StrictTransportSecurityHeader.NAME, StrictTransportSecurityHeader.VALUE);
    }

    /** Expected {@code Strict-Transport-Security} header. */
    private static final class StrictTransportSecurityHeader {

        static final String NAME = "Strict-Transport-Security";
        static final String VALUE = "max-age=31536000 ; includeSubDomains";

        private StrictTransportSecurityHeader() {
        }
    }
}
