package com.ticketing.business.usecase;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.ProcessOrderPaymentRequest;
import reactor.core.publisher.Mono;

public interface ProcessOrderPaymentUseCase {
    Mono<OrderData> execute(ProcessOrderPaymentRequest request);
}
