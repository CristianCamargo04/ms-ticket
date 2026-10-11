package com.ticketing.config.web;

import java.util.Objects;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a {@link ProblemDetail} directly to the response, for the components that run outside the controller
 * advice: security entry points and web filters. The body is serialized exactly like the controller responses
 * (extension members flattened at the top level).
 */
@Component
public class ProblemDetailResponseWriter {

    private final JsonMapper jsonMapper;

    /**
     * Creates the writer.
     *
     * @param jsonMapper the application JSON mapper; the problem-detail mix-in is (re)applied so the extension
     *                   members are always flattened
     */
    public ProblemDetailResponseWriter(JsonMapper jsonMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper").rebuild()
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                .build();
    }

    /**
     * Writes the problem with its status and {@code application/problem+json}. Headers set beforehand (e.g.
     * {@code Retry-After}, {@code WWW-Authenticate}) are kept. Nothing is written when the response is already
     * committed.
     *
     * @param exchange the current exchange
     * @param problem  the problem to write
     * @return completion when the body is written
     */
    public Mono<Void> write(ServerWebExchange exchange, ProblemDetail problem) {
        Objects.requireNonNull(problem, "problem");
        return Mono.defer(() -> {
            ServerHttpResponse response = exchange.getResponse();
            if (response.isCommitted()) {
                return Mono.empty();
            }
            response.setStatusCode(HttpStatusCode.valueOf(problem.getStatus()));
            response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            byte[] body = jsonMapper.writeValueAsBytes(problem);
            return response.writeWith(Mono.fromSupplier(() -> response.bufferFactory().wrap(body)));
        });
    }
}
