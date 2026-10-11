package com.ticketing.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.config.web.ProblemDetailResponseWriter;
import com.ticketing.presentation.context.RequestContext;
import com.ticketing.presentation.impl.error.ProblemDetailFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SecurityProblemHandlerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProblemDetailFactory problems =
            new ProblemDetailFactory(Clock.fixed(Instant.parse("2026-10-10T10:00:00Z"), ZoneOffset.UTC));
    private final SecurityProblemHandler handler =
            new SecurityProblemHandler(problems, new ProblemDetailResponseWriter(JSON));

    private static MockServerWebExchange exchange() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/events"));
        exchange.getAttributes().put(RequestContext.CORRELATION_ID_KEY, "corr-1");
        return exchange;
    }

    private static JsonNode body(MockServerWebExchange exchange) {
        return JSON.readTree(exchange.getResponse().getBodyAsString().block());
    }

    @Test
    void shouldChallengeRequestsWithoutToken() {
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(handler.commence(exchange, new AuthenticationCredentialsNotFoundException("missing")))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = body(exchange);
        assertThat(body.get("errorCode").asString()).isEqualTo("UNAUTHORIZED");
        assertThat(body.get("detail").asString()).isEqualTo(SecurityProblemHandler.UNAUTHORIZED_DETAIL);
        assertThat(body.get("correlationId").asString()).isEqualTo("corr-1");
    }

    @Test
    void shouldReportInvalidTokensWithoutTheirValidationDetails() {
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(handler.commence(exchange, new InvalidBearerTokenException("Jwt expired at 09:00")))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain("expired");
    }

    @Test
    void shouldNotEchoUnexpectedErrorCodesInTheChallenge() {
        assertThat(SecurityProblemHandler.bearerChallenge(
                new OAuth2AuthenticationException(new OAuth2Error("bad\" code")))).isEqualTo("Bearer");
        assertThat(SecurityProblemHandler.bearerChallenge(
                new OAuth2AuthenticationException(new OAuth2Error("invalid_request"))))
                .isEqualTo("Bearer error=\"invalid_request\"");
    }

    @Test
    void shouldRenderAccessDeniedAsForbidden() {
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(handler.handle(exchange, new AccessDeniedException("Access Denied"))).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo(SecurityProblemHandler.INSUFFICIENT_SCOPE_CHALLENGE);
        JsonNode body = body(exchange);
        assertThat(body.get("status").asInt()).isEqualTo(403);
        assertThat(body.get("errorCode").asString()).isEqualTo("FORBIDDEN");
    }

}
