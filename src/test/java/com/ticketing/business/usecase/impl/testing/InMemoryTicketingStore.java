package com.ticketing.business.usecase.impl.testing;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.data.TicketData;
import com.ticketing.domain.data.TicketTransitionData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class InMemoryTicketingStore {
    private final Map<String, EventData> events = new HashMap<>();
    private final Map<String, NavigableMap<String, TicketData>> tickets = new HashMap<>();
    private final Map<String, OrderData> orders = new HashMap<>();
    private final List<TicketTransitionData> transitions = new ArrayList<>();

    <T> Mono<T> atomically(Supplier<T> operation) {
        return Mono.fromCallable(() -> {
            synchronized (this) {
                return operation.get();
            }
        }).subscribeOn(Schedulers.parallel());
    }

    Map<String, EventData> eventsTable() {
        return events;
    }

    Map<String, OrderData> ordersTable() {
        return orders;
    }

    NavigableMap<String, TicketData> ticketsOf(String eventId) {
        return tickets.computeIfAbsent(eventId, _ -> new TreeMap<>());
    }

    List<TicketTransitionData> transitionsTable() {
        return transitions;
    }

    public synchronized void seedEventWithAvailableTickets(EventData event) {
        events.put(event.eventId(), event);
        NavigableMap<String, TicketData> eventTickets = ticketsOf(event.eventId());
        for (int sequence = 1; sequence <= event.totalCapacity(); sequence++) {
            TicketData ticket = TicketData.available(event.eventId(), TicketData.ticketIdFor(sequence),
                    event.createdAt());
            eventTickets.put(ticket.ticketId(), ticket);
        }
    }

    public synchronized void seedOrder(OrderData order) {
        orders.put(order.orderId(), order);
    }

    public synchronized EventData event(String eventId) {
        return events.get(eventId);
    }

    public synchronized OrderData order(String orderId) {
        return orders.get(orderId);
    }

    public synchronized List<OrderData> orders() {
        return List.copyOf(orders.values());
    }

    public synchronized List<TicketData> tickets(String eventId) {
        return List.copyOf(ticketsOf(eventId).values());
    }

    public synchronized List<TicketTransitionData> transitions() {
        return List.copyOf(transitions);
    }
}
