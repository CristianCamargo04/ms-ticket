package com.ticketing.business.usecase;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.GetOrderRequest;
import reactor.core.publisher.Mono;

/**
 * Reads an order with its ticket and processing statuses.
 */
public interface GetOrderStatusUseCase {

    /**
     * Reads an order; non-privileged requesters only see their own orders.
     *
     * @param request order id, requester and privilege flag
     * @return the order; error signal {@code OrderNotFoundException} when it does not exist or is not visible to
     *         the requester
     */
    Mono<OrderData> execute(GetOrderRequest request);
}
