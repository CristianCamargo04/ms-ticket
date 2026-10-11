package com.ticketing.config.messaging;

import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * Binds an SQS queue to the inbound channel that handles its messages.
 *
 * <p>The handler receives the raw message body. Completion acknowledges the message (it is deleted); an error signal
 * makes the {@link SqsListenerContainer} schedule a redelivery with exponential backoff, and the queue's redrive
 * policy eventually moves a message that keeps failing to its dead-letter queue. The handler runs with the
 * message's correlation id, message id and receive count in its Reactor context.
 *
 * @param queueName name of the queue (resolved to a URL when the container starts)
 * @param handler   processes one message body
 */
public record SqsQueueListener(String queueName, Function<String, Mono<Void>> handler) {

    /**
     * Validates the binding.
     *
     * @throws NullPointerException     when an argument is {@code null}
     * @throws IllegalArgumentException when the queue name is blank
     */
    public SqsQueueListener {
        if (Objects.requireNonNull(queueName, "queueName").isBlank()) {
            throw new IllegalArgumentException("queueName must not be blank");
        }
        Objects.requireNonNull(handler, "handler");
    }
}
