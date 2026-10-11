package com.ticketing.presentation.context;

import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

/**
 * Resolves the caller context of an authenticated request.
 */
public interface RequestContextResolver {

    /**
     * Builds the context from the JWT in {@code ReactiveSecurityContextHolder} ({@code sub}, {@code ROLE_*}
     * authorities), the {@code X-Correlation-Id} header and the client IP (first {@code X-Forwarded-For} hop, else
     * the remote address).
     *
     * @param request the current request
     * @return the context; error signal when the request is not authenticated
     */
    Mono<RequestContext> resolve(ServerHttpRequest request);
}
