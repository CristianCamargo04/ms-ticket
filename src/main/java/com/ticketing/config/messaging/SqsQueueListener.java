package com.ticketing.config.messaging;

import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Mono;

public record SqsQueueListener(String queueName, Function<String, Mono<Void>> handler) {
    public SqsQueueListener {
        if (Objects.requireNonNull(queueName, "queueName").isBlank()) {
            throw new IllegalArgumentException("queueName must not be blank");
        }
        Objects.requireNonNull(handler, "handler");
    }
}
