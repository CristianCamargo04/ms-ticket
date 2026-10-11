package com.ticketing.business.usecase;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.CancelOrderRequest;
import reactor.core.publisher.Mono;

public interface CancelOrderUseCase {
    Mono<OrderData> execute(CancelOrderRequest request);
}
