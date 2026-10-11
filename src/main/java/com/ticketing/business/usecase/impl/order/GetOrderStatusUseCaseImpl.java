package com.ticketing.business.usecase.impl.order;

import com.ticketing.business.usecase.GetOrderStatusUseCase;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.exception.OrderNotFoundException;
import com.ticketing.domain.internal_request.GetOrderRequest;
import com.ticketing.persistence.OrderPersistence;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Reads an order. A non-privileged requester that does not own the order gets the same
 * {@link OrderNotFoundException} as for a missing order, so order ids cannot be probed (anti-IDOR).
 */
@Service
public class GetOrderStatusUseCaseImpl implements GetOrderStatusUseCase {

    private final OrderPersistence orderPersistence;

    /**
     * Creates the use case.
     *
     * @param orderPersistence orders table
     */
    public GetOrderStatusUseCaseImpl(OrderPersistence orderPersistence) {
        this.orderPersistence = orderPersistence;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<OrderData> execute(GetOrderRequest request) {
        return Mono.defer(() -> orderPersistence.findById(request.orderId()))
                .filter(order -> request.privileged() || order.isOwnedBy(request.requesterId()))
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(request.orderId())));
    }
}
