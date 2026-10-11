package com.ticketing.config.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.presentation.context.RequestContext;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CorrelationIdWebFilterTest {
    private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private final CorrelationIdWebFilter filter = new CorrelationIdWebFilter();

    private static final class CapturingChain implements WebFilterChain {
        private final AtomicReference<ServerWebExchange> exchange = new AtomicReference<>();
        private final AtomicReference<String> contextValue = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange next) {
            return Mono.deferContextual(context -> {
                exchange.set(next);
                contextValue.set(context.get(RequestContext.CORRELATION_ID_KEY));
                return Mono.empty();
            });
        }
    }

    private static MockServerWebExchange exchangeWith(String correlationId) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/api/v1/events");
        if (correlationId != null) {
            request.header(RequestContext.CORRELATION_ID_HEADER, correlationId);
        }
        return MockServerWebExchange.from(request);
    }

    @Test
    void shouldKeepAValidIncomingCorrelationId() {
        MockServerWebExchange exchange = exchangeWith("abc-123");
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        ServerWebExchange next = chain.exchange.get();
        assertThat(next.getRequest().getHeaders().getFirst(RequestContext.CORRELATION_ID_HEADER)).isEqualTo("abc-123");
        assertThat((String) next.getAttribute(RequestContext.CORRELATION_ID_KEY)).isEqualTo("abc-123");
        assertThat(chain.contextValue).hasValue("abc-123");
        assertThat(exchange.getResponse().getHeaders().getFirst(RequestContext.CORRELATION_ID_HEADER))
                .isEqualTo("abc-123");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"has spaces", "line\nbreak", "semi;colon", "under_score"})
    void shouldReplaceMissingOrInvalidCorrelationIds(String incoming) {
        MockServerWebExchange exchange = exchangeWith(incoming);
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        String generated = exchange.getResponse().getHeaders().getFirst(RequestContext.CORRELATION_ID_HEADER);
        assertThat(generated).matches(UUID_PATTERN);
        assertThat(chain.exchange.get().getRequest().getHeaders().getFirst(RequestContext.CORRELATION_ID_HEADER))
                .isEqualTo(generated);
        assertThat(chain.contextValue).hasValue(generated);
    }

    @Test
    void shouldRejectIdsLongerThan64Characters() {
        assertThat(CorrelationIdWebFilter.resolve("a".repeat(64))).isEqualTo("a".repeat(64));
        assertThat(CorrelationIdWebFilter.resolve("a".repeat(65))).matches(UUID_PATTERN);
    }

    @Test
    void shouldGenerateDistinctIds() {
        assertThat(CorrelationIdWebFilter.resolve(null)).isNotEqualTo(CorrelationIdWebFilter.resolve(null));
    }

    @Test
    void shouldRunFirst() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
