package com.ticketing.persistence;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.data.TicketData;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.persistence.impl.support.DynamoDbTableNames;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class PersistenceFixtures {
    public static final Instant NOW = Instant.parse("2026-10-10T12:00:00.123Z");
    public static final Instant EVENT_DATE = Instant.parse("2026-12-31T21:00:00Z");
    public static final String EVENT_ID = "8d0f6f0a-1111-4c3e-9a55-000000000001";
    public static final String ORDER_ID = "3a3b7c1e-2222-5d4e-8b66-000000000002";
    public static final String CUSTOMER_ID = "customer-1";
    public static final int SHARDS = 8;
    public static final DynamoDbTableNames TABLES = new DynamoDbTableNames("t-events", "t-tickets", "t-orders",
            "t-ticket-transitions", "t-locks", SHARDS);

    private PersistenceFixtures() {
    }

    public static EventData event() {
        return new EventData(EVENT_ID, "Concert", "Arena", EVENT_DATE, 100, new BigDecimal("49.90"), "USD",
                EventStatusEnum.ON_SALE, 90, 5, 3, 1, 1, 7, NOW.minusSeconds(3600), NOW);
    }

    public static TicketData availableTicket(String ticketId) {
        return TicketData.available(EVENT_ID, ticketId, NOW);
    }

    public static OrderData queuedOrder(OrderTypeEnum type, int quantity) {
        return OrderData.newQueued(ORDER_ID, EVENT_ID, CUSTOMER_ID, type, quantity, "request-hash",
                NOW.plusSeconds(30), NOW.minusSeconds(5));
    }

    public static OrderData reservedOrder(List<String> ticketIds) {
        return queuedOrder(OrderTypeEnum.STANDARD, ticketIds.size())
                .withReservation(ticketIds, NOW.plus(Duration.ofMinutes(10)), NOW.minusSeconds(4));
    }

    public static OrderData pendingOrder(List<String> ticketIds) {
        return reservedOrder(ticketIds).withPendingConfirmation(NOW.plus(Duration.ofMinutes(5)), NOW.minusSeconds(3));
    }

    public static <T> CompletableFuture<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    public static <T> CompletableFuture<T> failed(Throwable error) {
        return CompletableFuture.failedFuture(error);
    }
}
