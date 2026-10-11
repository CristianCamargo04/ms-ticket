package com.ticketing.business.usecase.impl.order;

import com.ticketing.business.usecase.GetOrderStatusUseCase;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.exception.OrderNotFoundException;
import com.ticketing.domain.internal_request.GetOrderRequest;
import com.ticketing.persistence.OrderPersistence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
@Service
public class GetOrderStatusUseCaseImpl implements GetOrderStatusUseCase {
    private final OrderPersistence orderPersistence;

    @Override
    public Mono<OrderData> execute(GetOrderRequest request) {
        return Mono.defer(() -> orderPersistence.findById(request.orderId()))
                .filter(order -> request.privileged() || order.isOwnedBy(request.requesterId()))
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(request.orderId())));
    }
}
