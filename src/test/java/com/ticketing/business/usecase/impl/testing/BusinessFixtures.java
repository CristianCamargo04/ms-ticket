package com.ticketing.business.usecase.impl.testing;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.data.TicketData;
import com.ticketing.domain.event.EventPolicy;
import com.ticketing.domain.inventory.RetryPolicy;
import com.ticketing.domain.inventory.SweeperPolicy;
import com.ticketing.domain.order.OrderPolicy;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

public final class BusinessFixtures {
    public static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    public static final String EVENT_ID = "11111111-1111-1111-1111-111111111111";
    public static final String ORDER_ID = "22222222-2222-2222-2222-222222222222";
    public static final String CUSTOMER_ID = "customer-1";
    public static final String OTHER_CUSTOMER_ID = "customer-2";
    public static final String REQUEST_HASH = "hash-1";
    public static final String CORRELATION_ID = "corr-1";
    public static final BigDecimal PRICE = new BigDecimal("25.50");
    public static final String CURRENCY = "USD";

    public static final OrderPolicy ORDER_POLICY = new OrderPolicy(Duration.ofMinutes(10), Duration.ofMinutes(5),
            Duration.ofSeconds(30), 10, 40, 3);

    public static final EventPolicy EVENT_POLICY = new EventPolicy(20_000, Duration.ofSeconds(1), 4);

    public static final SweeperPolicy SWEEPER_POLICY = new SweeperPolicy(Duration.ofSeconds(30), 100, 8,
            "instance-1");

    public static final RetryPolicy INVENTORY_RETRY_POLICY = new RetryPolicy(6, Duration.ofMillis(25),
            Duration.ofSeconds(1), 0.5);

    public static final RetryPolicy PUBLISH_RETRY_POLICY = new RetryPolicy(3, Duration.ofMillis(100),
            Duration.ofSeconds(1), 0.5);

    public static final Duration RETRY_HORIZON = Duration.ofSeconds(30);

    private BusinessFixtures() {
    }

    public static EventData event(EventStatusEnum status, int capacity, int available) {
        return new EventData(EVENT_ID, "Concert", "Arena", NOW.plus(Duration.ofDays(30)), capacity, PRICE, CURRENCY,
                status, available, capacity - available, 0, 0, 0, 3L, NOW.minusSeconds(3600), NOW.minusSeconds(60));
    }

    public static EventData onSaleEvent(int capacity, int available) {
        return event(EventStatusEnum.ON_SALE, capacity, available);
    }

    public static OrderData queuedOrder(String orderId, OrderTypeEnum type, int quantity) {
        return OrderData.newQueued(orderId, EVENT_ID, CUSTOMER_ID, type, quantity, REQUEST_HASH,
                ORDER_POLICY.dispatchDeadline(NOW.minusSeconds(60)), NOW.minusSeconds(60));
    }

    public static OrderData queuedOrder(OrderTypeEnum type, int quantity) {
        return queuedOrder(ORDER_ID, type, quantity);
    }

    public static OrderData queuedOrder(int quantity) {
        return queuedOrder(OrderTypeEnum.STANDARD, quantity);
    }

    public static OrderData reservedOrder(String orderId, int quantity, Instant holdExpiresAt) {
        return queuedOrder(orderId, OrderTypeEnum.STANDARD, quantity)
                .withReservation(ticketIds(quantity), holdExpiresAt, NOW.minusSeconds(30));
    }

    public static OrderData reservedOrder(int quantity, Instant holdExpiresAt) {
        return reservedOrder(ORDER_ID, quantity, holdExpiresAt);
    }

    public static OrderData reservedOrder(int quantity) {
        return reservedOrder(quantity, NOW.plus(Duration.ofMinutes(5)));
    }

    public static OrderData expiredHoldOrder(String orderId, int quantity) {
        return reservedOrder(orderId, quantity, NOW.minusSeconds(1));
    }

    public static OrderData pendingOrder(String orderId, int quantity) {
        return reservedOrder(orderId, quantity, NOW.plus(Duration.ofMinutes(5)))
                .withPendingConfirmation(NOW.plus(Duration.ofMinutes(4)), NOW.minusSeconds(10));
    }

    public static OrderData pendingOrder(int quantity) {
        return pendingOrder(ORDER_ID, quantity);
    }

    public static OrderData timedOutPendingOrder(String orderId, int quantity) {
        return reservedOrder(orderId, quantity, NOW.plus(Duration.ofMinutes(5)))
                .withPendingConfirmation(NOW.minusSeconds(1), NOW.minusSeconds(300));
    }

    public static OrderData soldOrder(int quantity) {
        return pendingOrder(quantity).withSale("pay-1", NOW.minusSeconds(5));
    }

    public static List<String> ticketIds(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(TicketData::ticketIdFor).toList();
    }

    public static TicketData availableTicket(int sequence) {
        return TicketData.available(EVENT_ID, TicketData.ticketIdFor(sequence), NOW.minusSeconds(3600));
    }
}
