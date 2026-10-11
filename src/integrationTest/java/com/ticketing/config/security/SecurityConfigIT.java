package com.ticketing.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.nimbusds.jwt.SignedJWT;
import com.ticketing.business.usecase.CancelOrderUseCase;
import com.ticketing.business.usecase.ConfirmOrderUseCase;
import com.ticketing.business.usecase.GetOrderStatusUseCase;
import com.ticketing.business.usecase.PlaceOrderUseCase;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.PlaceOrderRequest;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.presentation.OrderRestController;
import com.ticketing.presentation.impl.OrderRestControllerImpl;
import java.time.Duration;
import java.text.ParseException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

@Testcontainers
@WebFluxTest(controllers = OrderRestControllerImpl.class)
@Import(SecurityIntegrationTestConfig.class)
class SecurityConfigIT {
    private static final int PORT = 8080;
    private static final String AUDIENCE = "ms-ticket";
    private static final String IDEMPOTENCY_KEY = "it-6f1c2a9e-0b7d-4c55";
    private static final String FOREIGN_ISSUER = "https://evil.example/default";

    private static final String ISSUER_CONFIG = """
            {"interactiveLogin": false, "tokenCallbacks": [{"issuerId": "default", "tokenExpiry": 300,
              "requestMappings": [
                {"requestParam": "scope", "match": "customer",
                 "claims": {"sub": "customer-it", "aud": ["ms-ticket"], "roles": ["customer"]}},
                {"requestParam": "scope", "match": "no-role",
                 "claims": {"sub": "user-it", "aud": ["ms-ticket"]}},
                {"requestParam": "scope", "match": "wrong-audience",
                 "claims": {"sub": "customer-it", "aud": ["another-api"], "roles": ["customer"]}},
                {"requestParam": "scope", "match": "foreign-issuer",
                 "claims": {"sub": "customer-it", "aud": ["ms-ticket"], "roles": ["customer"],
                            "iss": "https://evil.example/default"}}]}]}
            """;

    @Container
    static final GenericContainer<?> ISSUER = new GenericContainer<>(
            DockerImageName.parse("ghcr.io/navikt/mock-oauth2-server:6.0.5"))
            .withExposedPorts(PORT)
            .withEnv("JSON_CONFIG", ISSUER_CONFIG)
            .waitingFor(Wait.forHttp("/default/.well-known/openid-configuration").forStatusCode(200));

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private PlaceOrderUseCase placeOrderUseCase;

    @MockitoBean
    private GetOrderStatusUseCase getOrderStatusUseCase;

    @MockitoBean
    private ConfirmOrderUseCase confirmOrderUseCase;

    @MockitoBean
    private CancelOrderUseCase cancelOrderUseCase;

    @DynamicPropertySource
    static void issuerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuer("default"));
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> issuer("default") + "/jwks");
        registry.add("ticketing.security.issuer", () -> issuer("default"));
        registry.add("ticketing.security.audience", () -> AUDIENCE);
    }

    private static String issuer(String issuerId) {
        return "http://" + ISSUER.getHost() + ":" + ISSUER.getMappedPort(PORT) + "/" + issuerId;
    }

    private static String token(String issuerId, String scope) {
        Map<String, Object> response = WebClient.create(issuer(issuerId)).post().uri("/token")
                .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                        .with("client_id", "ms-ticket-it")
                        .with("client_secret", "not-a-secret")
                        .with("scope", scope))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block(Duration.ofSeconds(10));
        assertThat(response).containsKey("access_token");
        return (String) response.get("access_token");
    }

    private WebTestClient.ResponseSpec placeOrder(String accessToken) {
        return webTestClient.post().uri("/api/v1/events/e-1/orders")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .header(OrderRestController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"quantity\":2}")
                .exchange();
    }

    @Test
    void shouldAcceptSignedTokensOfTheIssuerForTheAudienceAndMapTheirRoles() {
        Instant now = Instant.now();
        given(placeOrderUseCase.execute(any())).willReturn(Mono.just(OrderData.newQueued("o-it", "e-1",
                "customer-it", OrderTypeEnum.STANDARD, 2, "hash", now.plusSeconds(30), now)));

        placeOrder(token("default", "customer"))
                .expectStatus().isAccepted()
                .expectHeader().location("/api/v1/orders/o-it");

        ArgumentCaptor<PlaceOrderRequest> request = ArgumentCaptor.forClass(PlaceOrderRequest.class);
        verify(placeOrderUseCase).execute(request.capture());
        assertThat(request.getValue().customerId()).isEqualTo("customer-it");
    }

    @Test
    void shouldForbidValidTokensWithoutTheRequiredRole() {
        placeOrder(token("default", "no-role"))
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("FORBIDDEN");
        verifyNoInteractions(placeOrderUseCase);
    }

    @Test
    void shouldForbidCustomersOnAdministratorRoutes() {
        webTestClient.post().uri("/api/v1/events")
                .headers(headers -> headers.setBearerAuth(token("default", "customer")))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void shouldRejectTokensIssuedForAnotherAudience() {
        placeOrder(token("default", "wrong-audience"))
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("UNAUTHORIZED");
        verifyNoInteractions(placeOrderUseCase);
    }

    @Test
    void shouldRejectTokensSignedByTheTrustedKeyButIssuedByAnotherIssuer() throws ParseException {
        String accessToken = token("default", "foreign-issuer");
        SignedJWT jwt = SignedJWT.parse(accessToken);

        assertThat(jwt.getHeader().getKeyID()).isEqualTo(SignedJWT.parse(token("default", "customer"))
                .getHeader().getKeyID());
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo(FOREIGN_ISSUER);
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly(AUDIENCE);

        placeOrder(accessToken)
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("UNAUTHORIZED");
        verifyNoInteractions(placeOrderUseCase);
    }

    @Test
    void shouldRejectTokensSignedByAnUnknownKey() throws ParseException {
        String accessToken = token("other", "customer");
        assertThat(SignedJWT.parse(accessToken).getHeader().getKeyID()).isEqualTo("other");

        for (int i = 0; i < 5; i++) {
            placeOrder(accessToken)
                    .expectStatus().isUnauthorized()
                    .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
        }
        verifyNoInteractions(placeOrderUseCase);
    }

    @Test
    void shouldRejectTamperedTokens() {
        String[] parts = token("default", "customer").split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + new StringBuilder(parts[2]).reverse();

        placeOrder(tampered)
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("UNAUTHORIZED");
        verifyNoInteractions(placeOrderUseCase);
    }

    @Test
    void shouldRejectRequestsWithoutToken() {
        webTestClient.post().uri("/api/v1/events/e-1/orders")
                .header(OrderRestController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"quantity\":2}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }
}
