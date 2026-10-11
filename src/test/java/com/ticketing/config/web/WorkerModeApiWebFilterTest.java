package com.ticketing.config.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.presentation.context.RequestContext;
import com.ticketing.presentation.impl.error.ProblemDetailFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

class WorkerModeApiWebFilterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T10:00:00Z"), ZoneOffset.UTC);

    private final WorkerModeApiWebFilter filter = new WorkerModeApiWebFilter(new ProblemDetailFactory(CLOCK),
            new ProblemDetailResponseWriter(JsonMapper.builder().build()));

    private final AtomicInteger chainCalls = new AtomicInteger();
    private final WebFilterChain chain = exchange -> Mono.fromRunnable(chainCalls::incrementAndGet);

    private static MockServerWebExchange exchange(HttpMethod method, String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path));
        exchange.getAttributes().put(RequestContext.CORRELATION_ID_KEY, "corr-42");
        return exchange;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api", "/api/", "/api/v1/events", "/api/v1/events/e-1/orders", "/api/v1/orders/o-1"})
    void shouldAnswerNotFoundToEveryApiRequest(String path) {
        MockServerWebExchange exchange = exchange(HttpMethod.POST, path);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalls).hasValue(0);
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body)
                        .contains("\"status\":404", "\"title\":\"Not Found\"", "\"correlationId\":\"corr-42\"",
                                WorkerModeApiWebFilter.DETAIL)
                        .doesNotContain("worker"))
                .verifyComplete();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/actuator/health", "/actuator/health/readiness", "/actuator/prometheus", "/apis",
            "/", "/internal/api/v1"})
    void shouldLetEverythingElseThrough(String path) {
        MockServerWebExchange exchange = exchange(HttpMethod.GET, path);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chainCalls).hasValue(1);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void shouldRunBeforeSpringSecurityAndAfterTheCorrelationFilter() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1)
                .isGreaterThan(new CorrelationIdWebFilter().getOrder())
                .isLessThan(-100);
    }

    @Test
    void shouldRequireItsCollaborators() {
        ProblemDetailResponseWriter writer = new ProblemDetailResponseWriter(JsonMapper.builder().build());
        assertThatNullPointerException().isThrownBy(() -> new WorkerModeApiWebFilter(null, writer));
        assertThatNullPointerException()
                .isThrownBy(() -> new WorkerModeApiWebFilter(new ProblemDetailFactory(CLOCK), null));
    }
}
