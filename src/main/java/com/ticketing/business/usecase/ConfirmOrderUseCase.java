package com.ticketing.business.usecase;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.ConfirmOrderRequest;
import reactor.core.publisher.Mono;

public interface ConfirmOrderUseCase {
    Mono<OrderData> execute(ConfirmOrderRequest request);
}
