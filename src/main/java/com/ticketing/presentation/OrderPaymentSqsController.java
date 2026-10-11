package com.ticketing.presentation;

import reactor.core.publisher.Mono;

public interface OrderPaymentSqsController {
    Mono<Void> handle(String payload);
}
