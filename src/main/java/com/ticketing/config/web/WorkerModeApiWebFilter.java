package com.ticketing.config.web;

import com.ticketing.presentation.impl.error.ProblemDetailFactory;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Hides the REST API on worker-only instances ({@code ticketing.runtime-mode=worker}, ADR-008).
 *
 * <p>A worker keeps its HTTP server running for the actuator (ALB / ECS health checks, Prometheus scraping), but it
 * must not serve business traffic: every request under {@value #API_PREFIX} is answered {@code 404} with a problem
 * detail, whatever its method or credentials. The filter runs right after {@link CorrelationIdWebFilter} and before
 * Spring Security, so an anonymous caller gets the same {@code 404} as an authenticated one (no route discovery
 * through 401/403) and no use case is ever reached. Everything else, notably {@code /actuator/**}, passes through.
 */
public class WorkerModeApiWebFilter implements WebFilter, Ordered {

    /** Right after the correlation filter, long before Spring Security ({@code -100}). */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    /** Path prefix of the business API. */
    public static final String API_PREFIX = "/api/";

    /** Detail of the 404 problem (does not reveal that the instance is a worker). */
    static final String DETAIL = "No resource exists at this path";

    private final ProblemDetailFactory problems;
    private final ProblemDetailResponseWriter writer;

    /**
     * Creates the filter.
     *
     * @param problems builds the 404 problem
     * @param writer   writes it to the response
     */
    public WorkerModeApiWebFilter(ProblemDetailFactory problems, ProblemDetailResponseWriter writer) {
        this.problems = Objects.requireNonNull(problems, "problems");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isApiRequest(exchange.getRequest())) {
            return chain.filter(exchange);
        }
        return writer.write(exchange, problems.forStatus(HttpStatus.NOT_FOUND, DETAIL, exchange));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * Whether a request targets the business API: its path is {@code /api} or starts with {@value #API_PREFIX}.
     *
     * @param request the request
     * @return {@code true} for API requests
     */
    static boolean isApiRequest(ServerHttpRequest request) {
        String path = request.getPath().pathWithinApplication().value();
        return path.equals("/api") || path.startsWith(API_PREFIX);
    }
}
