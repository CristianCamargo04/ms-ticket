package com.ticketing.presentation.context;

import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

/**
 * Resolves the caller context of an authenticated request.
 */
public interface RequestContextResolver {

    /**
     * Builds the context from the JWT in {@code ReactiveSecurityContextHolder} ({@code sub}, {@code ROLE_*}
     * authorities), the {@code X-Correlation-Id} header and the client IP.
     *
     * <p>The client IP is the <em>rightmost</em> {@code X-Forwarded-For} hop, else the remote address. The service
     * sits behind a single trusted proxy (the AWS ALB), which appends the address of its peer to that header: the
     * rightmost hop is therefore the address the proxy saw, while every hop to its left (including the first one) is
     * supplied by the client and spoofable. The value is informational (logs, rate-limit key of anonymous callers).
     *
     * @param request the current request
     * @return the context; error signal when the request is not authenticated
     */
    Mono<RequestContext> resolve(ServerHttpRequest request);
}
