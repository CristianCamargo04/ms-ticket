package com.ticketing.config.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProblemDetailResponseWriterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProblemDetailResponseWriter writer = new ProblemDetailResponseWriter(JSON);

    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "slow down");
        problem.setTitle("RATE_LIMIT_EXCEEDED");
        problem.setInstance(URI.create("/api/v1/events/e-1/orders"));
        problem.setProperty("errorCode", "RATE_LIMIT_EXCEEDED");
        problem.setProperty("timestamp", Instant.parse("2026-10-10T10:00:00Z"));
        return problem;
    }

    @Test
    void shouldWriteTheProblemWithFlattenedExtensionMembers() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api"));
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "3");

        StepVerifier.create(writer.write(exchange, problem())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3");
        JsonNode body = JSON.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("title").asString()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(body.get("detail").asString()).isEqualTo("slow down");
        assertThat(body.get("instance").asString()).isEqualTo("/api/v1/events/e-1/orders");
        assertThat(body.get("errorCode").asString()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(body.get("timestamp").asString()).isEqualTo("2026-10-10T10:00:00Z");
        assertThat(body.has("properties")).isFalse();
    }

    @Test
    void shouldNotWriteOnACommittedResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api"));
        exchange.getResponse().setStatusCode(HttpStatus.ACCEPTED);
        StepVerifier.create(exchange.getResponse().setComplete()).verifyComplete();

        StepVerifier.create(writer.write(exchange, problem())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void shouldRequireItsArguments() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api"));

        assertThatNullPointerException().isThrownBy(() -> new ProblemDetailResponseWriter(null));
        assertThatNullPointerException().isThrownBy(() -> writer.write(exchange, null));
    }
}
