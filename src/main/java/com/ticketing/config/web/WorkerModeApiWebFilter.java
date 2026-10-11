package com.ticketing.config.web;

import com.ticketing.presentation.impl.error.ProblemDetailFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class WorkerModeApiWebFilter implements WebFilter, Ordered {
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    public static final String API_PREFIX = "/api/";

    static final String DETAIL = "No resource exists at this path";

    private final ProblemDetailFactory problems;
    private final ProblemDetailResponseWriter writer;

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

    static boolean isApiRequest(ServerHttpRequest request) {
        String path = request.getPath().pathWithinApplication().value();
        return path.equals("/api") || path.startsWith(API_PREFIX);
    }
}
