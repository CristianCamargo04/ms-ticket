package com.ticketing.business.usecase.impl.order;

import com.ticketing.business.usecase.IssueComplimentaryTicketsUseCase;
import com.ticketing.cache.EventCacheService;
import com.ticketing.client.sdk.SqsOrderQueueClient;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.IssueComplimentaryRequest;
import com.ticketing.domain.inventory.RetryPolicy;
import com.ticketing.domain.order.OrderPolicy;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.persistence.EventPersistence;
import com.ticketing.persistence.OrderPersistence;
import com.ticketing.util.DeterministicIdUtil;
import java.time.Clock;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Accepts an administrative complimentary issue: the order belongs to the beneficiary, its id is derived from the
 * issuing administrator and the idempotency key, and it is queued like a purchase (see {@link QueuedOrderAdmission})
 * without the soft availability check; the order worker issues the tickets.
 */
@Service
public class IssueComplimentaryTicketsUseCaseImpl implements IssueComplimentaryTicketsUseCase {

    private final QueuedOrderAdmission admission;
    private final OrderPolicy orderPolicy;

    /**
     * Creates the use case.
     *
     * @param orderPersistence    orders table
     * @param eventPersistence    events table (cache loader)
     * @param eventCacheService   event cache
     * @param sqsOrderQueueClient order queues
     * @param orderPolicy         order rules
     * @param publishRetryPolicy  retries of the reservation message publication
     * @param clock               time source
     */
    public IssueComplimentaryTicketsUseCaseImpl(OrderPersistence orderPersistence, EventPersistence eventPersistence,
                                                EventCacheService eventCacheService,
                                                SqsOrderQueueClient sqsOrderQueueClient, OrderPolicy orderPolicy,
                                                RetryPolicy publishRetryPolicy, Clock clock) {
        this.admission = new QueuedOrderAdmission(orderPersistence, eventPersistence, eventCacheService,
                sqsOrderQueueClient, orderPolicy, publishRetryPolicy, clock);
        this.orderPolicy = orderPolicy;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<OrderData> execute(IssueComplimentaryRequest request) {
        return Mono.defer(() -> {
            QueuedOrderAdmission.requireQuantity(request.quantity(), orderPolicy.maxComplimentaryPerRequest());
            String orderId = DeterministicIdUtil.nameBasedId(request.issuedBy() + ":" + request.idempotencyKey());
            return admission.admit(new QueuedOrderAdmission.Admission(orderId, request.eventId(),
                    request.beneficiaryId(), OrderTypeEnum.COMPLIMENTARY, request.quantity(),
                    request.idempotencyKey(), request.requestHash(), request.correlationId(), false));
        });
    }
}
