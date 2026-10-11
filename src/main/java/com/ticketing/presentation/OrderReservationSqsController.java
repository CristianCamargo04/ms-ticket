package com.ticketing.presentation;

import reactor.core.publisher.Mono;

/**
 * Inbound channel of the order-reservation queue (registered by the SQS listener container).
 */
public interface OrderReservationSqsController {

    /**
     * Handles one message body: parses an {@code OrderReservationMessageRequestDto} and runs the reserve-tickets use
     * case. Completion acknowledges (deletes) the message.
     *
     * @param payload the raw JSON body
     * @return completion when processed or when a non-retryable business error was logged; error signal for a
     *         malformed payload or a retryable failure (the message is redelivered, then dead-lettered)
     */
    Mono<Void> handle(String payload);
}
