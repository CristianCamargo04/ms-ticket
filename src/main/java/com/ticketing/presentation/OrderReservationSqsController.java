package com.ticketing.presentation;

import reactor.core.publisher.Mono;

public interface OrderReservationSqsController {
    Mono<Void> handle(String payload);
}
