package com.ticketing.business.usecase.impl.order;

import com.ticketing.business.usecase.PlaceOrderUseCase;
import com.ticketing.cache.EventCacheService;
import com.ticketing.client.sdk.SqsOrderQueueClient;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.PlaceOrderRequest;
import com.ticketing.domain.inventory.RetryPolicy;
import com.ticketing.domain.order.OrderPolicy;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.persistence.EventPersistence;
import com.ticketing.persistence.OrderPersistence;
import com.ticketing.util.DeterministicIdUtil;
import java.time.Clock;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class PlaceOrderUseCaseImpl implements PlaceOrderUseCase {
    private final QueuedOrderAdmission admission;
    private final OrderPolicy orderPolicy;

    public PlaceOrderUseCaseImpl(OrderPersistence orderPersistence, EventPersistence eventPersistence,
                                 EventCacheService eventCacheService, SqsOrderQueueClient sqsOrderQueueClient,
                                 OrderPolicy orderPolicy, RetryPolicy publishRetryPolicy, Clock clock) {
        this.admission = new QueuedOrderAdmission(orderPersistence, eventPersistence, eventCacheService,
                sqsOrderQueueClient, orderPolicy, publishRetryPolicy, clock);
        this.orderPolicy = orderPolicy;
    }

    @Override
    public Mono<OrderData> execute(PlaceOrderRequest request) {
        return Mono.defer(() -> {
            QueuedOrderAdmission.requireQuantity(request.quantity(), orderPolicy.maxTicketsPerOrder());
            String orderId = DeterministicIdUtil.nameBasedId(request.customerId() + ":" + request.idempotencyKey());
            return admission.admit(new QueuedOrderAdmission.Admission(orderId, request.eventId(),
                    request.customerId(), OrderTypeEnum.STANDARD, request.quantity(), request.idempotencyKey(),
                    request.requestHash(), request.correlationId(), true));
        });
    }
}
