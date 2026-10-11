package com.ticketing.client.sdk;

import com.ticketing.domain.internal_request.OrderReservationMessage;
import com.ticketing.domain.internal_request.PaymentRequestedMessage;
import reactor.core.publisher.Mono;

/**
 * Publishes order messages to SQS. Any failure is an error signal {@code MessagePublishingException} (retryable).
 */
public interface SqsOrderQueueClient {

    /**
     * Publishes to the order-reservation queue.
     *
     * @param message the message
     * @return the SQS message id
     */
    Mono<String> publishReservationRequested(OrderReservationMessage message);

    /**
     * Publishes to the order-payment queue.
     *
     * @param message the message
     * @return the SQS message id
     */
    Mono<String> publishPaymentRequested(PaymentRequestedMessage message);
}
