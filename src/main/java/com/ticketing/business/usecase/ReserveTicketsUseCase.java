package com.ticketing.business.usecase;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.ReserveTicketsRequest;
import reactor.core.publisher.Mono;

public interface ReserveTicketsUseCase {
    Mono<OrderData> execute(ReserveTicketsRequest request);
}
