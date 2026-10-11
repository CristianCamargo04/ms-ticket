package com.ticketing.business.usecase.impl.testing;

import com.ticketing.domain.data.TicketData;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.persistence.TicketPersistence;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * In-memory {@link TicketPersistence} that emulates the eventual consistency of the {@code eventId-statusKey-index}
 * GSI: {@link #findAvailable} reads a snapshot of the tickets that is only refreshed every {@code refreshEvery} calls,
 * so concurrent workers routinely see tickets as available after they were reserved (stale selections that the
 * conditional transaction must reject).
 */
public final class InMemoryTicketPersistence implements TicketPersistence {

    private final InMemoryTicketingStore store;
    private final int refreshEvery;
    private final Map<String, NavigableMap<String, TicketData>> indexSnapshots = new ConcurrentHashMap<>();
    private long calls;

    /**
     * Creates the fake.
     *
     * @param store        shared state
     * @param refreshEvery the index snapshot is refreshed on one call out of {@code refreshEvery} (1 = always fresh)
     */
    public InMemoryTicketPersistence(InMemoryTicketingStore store, int refreshEvery) {
        this.store = store;
        this.refreshEvery = refreshEvery;
    }

    @Override
    public Mono<Long> saveAll(Flux<TicketData> tickets) {
        return tickets.collectList()
                .flatMap(batch -> store.atomically(() -> {
                    batch.forEach(ticket -> store.ticketsOf(ticket.eventId()).put(ticket.ticketId(), ticket));
                    return (long) batch.size();
                }));
    }

    @Override
    public Flux<TicketData> findAvailable(String eventId, String fromTicketId, int limit) {
        return store.atomically(() -> {
                    if (calls++ % refreshEvery == 0 || !indexSnapshots.containsKey(eventId)) {
                        indexSnapshots.put(eventId, new TreeMap<>(store.ticketsOf(eventId)));
                    }
                    return indexSnapshots.get(eventId).tailMap(fromTicketId, true).values().stream()
                            .filter(ticket -> ticket.status() == TicketStatusEnum.AVAILABLE)
                            .limit(limit)
                            .toList();
                })
                .flatMapIterable(List::copyOf);
    }

    @Override
    public Flux<TicketData> findByIds(String eventId, List<String> ticketIds) {
        return store.atomically(() -> ticketIds.stream()
                        .map(store.ticketsOf(eventId)::get)
                        .filter(Objects::nonNull)
                        .toList())
                .flatMapIterable(List::copyOf);
    }
}
