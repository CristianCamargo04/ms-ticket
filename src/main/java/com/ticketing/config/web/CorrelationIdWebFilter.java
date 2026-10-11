package com.ticketing.config.web;

import com.ticketing.presentation.context.RequestContext;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Assigns a correlation id to every request (first filter of the chain). A valid incoming
 * {@value RequestContext#CORRELATION_ID_HEADER} header is kept; a missing or invalid one (it could carry log-injection
 * payloads) is replaced by a random UUID. The id is written back on the request header (read by the controllers),
 * stored as the exchange attribute and Reactor context key {@value RequestContext#CORRELATION_ID_KEY}, and echoed in
 * the response.
 */
@Component
public class CorrelationIdWebFilter implements WebFilter, Ordered {

    /** Accepted incoming correlation ids. */
    public static final Pattern CORRELATION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = resolve(
                exchange.getRequest().getHeaders().getFirst(RequestContext.CORRELATION_ID_HEADER));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(RequestContext.CORRELATION_ID_HEADER, correlationId))
                .build();
        ServerWebExchange correlatedExchange = exchange.mutate().request(request).build();
        correlatedExchange.getAttributes().put(RequestContext.CORRELATION_ID_KEY, correlationId);
        correlatedExchange.getResponse().getHeaders().set(RequestContext.CORRELATION_ID_HEADER, correlationId);
        return chain.filter(correlatedExchange)
                .contextWrite(Context.of(RequestContext.CORRELATION_ID_KEY, correlationId));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * Keeps a valid incoming id or generates a new one.
     *
     * @param incoming the incoming header value, nullable
     * @return the correlation id of the request
     */
    static String resolve(String incoming) {
        return incoming != null && CORRELATION_ID_PATTERN.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
    }
}
