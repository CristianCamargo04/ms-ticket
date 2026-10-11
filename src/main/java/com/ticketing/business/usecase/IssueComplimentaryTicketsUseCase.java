package com.ticketing.business.usecase;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.IssueComplimentaryRequest;
import reactor.core.publisher.Mono;

public interface IssueComplimentaryTicketsUseCase {
    Mono<OrderData> execute(IssueComplimentaryRequest request);
}
