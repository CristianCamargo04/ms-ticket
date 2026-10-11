package com.ticketing.business.usecase.impl.testing;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.PageData;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.persistence.EventPersistence;
import java.time.Instant;
import java.util.Comparator;
import reactor.core.publisher.Mono;

public final class InMemoryEventPersistence implements EventPersistence {
    private final InMemoryTicketingStore store;

    public InMemoryEventPersistence(InMemoryTicketingStore store) {
        this.store = store;
    }

    @Override
    public Mono<EventData> create(EventData event) {
        return store.atomically(() -> {
            if (store.eventsTable().putIfAbsent(event.eventId(), event) != null) {
                throw new IllegalStateException("Event " + event.eventId() + " already exists");
            }
            return event;
        });
    }

    @Override
    public Mono<EventData> findById(String eventId) {
        return store.atomically(() -> store.eventsTable().get(eventId));
    }

    @Override
    public Mono<PageData<EventData>> findByStatus(EventStatusEnum status, int limit, String cursor) {
        return store.atomically(() -> new PageData<>(store.eventsTable().values().stream()
                .filter(event -> event.status() == status)
                .sorted(Comparator.comparing(EventData::eventDate))
                .limit(limit)
                .toList(), null));
    }

    @Override
    public Mono<Boolean> updateStatus(String eventId, EventStatusEnum expected, EventStatusEnum target, Instant now) {
        return store.atomically(() -> {
            EventData event = store.eventsTable().get(eventId);
            if (event == null || event.status() != expected) {
                return false;
            }
            store.eventsTable().put(eventId, event.withStatus(target, now));
            return true;
        });
    }
}
