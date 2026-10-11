package com.ticketing.persistence;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.PageData;
import com.ticketing.enums.EventStatusEnum;
import java.time.Instant;
import reactor.core.publisher.Mono;

public interface EventPersistence {
    Mono<EventData> create(EventData event);

    Mono<EventData> findById(String eventId);

    Mono<PageData<EventData>> findByStatus(EventStatusEnum status, int limit, String cursor);

    Mono<Boolean> updateStatus(String eventId, EventStatusEnum expected, EventStatusEnum target, Instant now);
}
