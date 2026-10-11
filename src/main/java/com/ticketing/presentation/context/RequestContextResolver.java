package com.ticketing.presentation.context;

import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

public interface RequestContextResolver {
    Mono<RequestContext> resolve(ServerHttpRequest request);
}
