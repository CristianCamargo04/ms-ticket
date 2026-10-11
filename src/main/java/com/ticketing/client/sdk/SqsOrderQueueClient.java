package com.ticketing.client.sdk;

import com.ticketing.domain.internal_request.OrderReservationMessage;
import com.ticketing.domain.internal_request.PaymentRequestedMessage;
import reactor.core.publisher.Mono;

public interface SqsOrderQueueClient {
    Mono<String> publishReservationRequested(OrderReservationMessage message);

    Mono<String> publishPaymentRequested(PaymentRequestedMessage message);
}
