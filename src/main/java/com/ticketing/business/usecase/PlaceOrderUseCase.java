package com.ticketing.business.usecase;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.PlaceOrderRequest;
import reactor.core.publisher.Mono;

public interface PlaceOrderUseCase {
    Mono<OrderData> execute(PlaceOrderRequest request);
}
