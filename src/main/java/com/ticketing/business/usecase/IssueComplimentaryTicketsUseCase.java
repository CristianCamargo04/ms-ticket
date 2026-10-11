package com.ticketing.business.usecase;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.IssueComplimentaryRequest;
import reactor.core.publisher.Mono;

/**
 * Accepts an administrative complimentary issue and queues it (processed like a reservation).
 */
public interface IssueComplimentaryTicketsUseCase {

    /**
     * Idempotently creates a {@code QUEUED} complimentary order (id derived from issuer and idempotency key) and
     * publishes its reservation message.
     *
     * @param request the complimentary request
     * @return the queued (or previously created) order; error signals {@code DomainValidationException},
     *         {@code IdempotencyKeyConflictException}, {@code EventNotFoundException},
     *         {@code EventNotOnSaleException}
     */
    Mono<OrderData> execute(IssueComplimentaryRequest request);
}
