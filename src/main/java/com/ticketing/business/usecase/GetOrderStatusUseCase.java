package com.ticketing.business.usecase;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.GetOrderRequest;
import reactor.core.publisher.Mono;

public interface GetOrderStatusUseCase {
    Mono<OrderData> execute(GetOrderRequest request);
}
