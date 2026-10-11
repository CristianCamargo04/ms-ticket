package com.ticketing.business.usecase.impl.event;

import com.ticketing.business.usecase.ListEventsUseCase;
import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.PageData;
import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.domain.internal_request.ListEventsRequest;
import com.ticketing.persistence.EventPersistence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
@Service
public class ListEventsUseCaseImpl implements ListEventsUseCase {
    static final int MIN_LIMIT = 1;

    static final int MAX_LIMIT = 100;

    private final EventPersistence eventPersistence;

    @Override
    public Mono<PageData<EventData>> execute(ListEventsRequest request) {
        return Mono.defer(() -> {
            if (request.status() == null) {
                return Mono.error(new DomainValidationException("status is required"));
            }
            int limit = Math.clamp(request.limit(), MIN_LIMIT, MAX_LIMIT);
            return eventPersistence.findByStatus(request.status(), limit, request.cursor());
        });
    }
}
