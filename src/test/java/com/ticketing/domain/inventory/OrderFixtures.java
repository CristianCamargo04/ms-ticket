package com.ticketing.domain.inventory;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.data.TicketData;
import com.ticketing.enums.OrderTypeEnum;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Orders in typical states for the inventory command tests.
 */
final class OrderFixtures {

    static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
    static final Instant HOLD_EXPIRES_AT = NOW.plusSeconds(600);
    static final List<String> TICKETS = List.of("T-000001", "T-000002");

    private OrderFixtures() {
    }

    static OrderData queued(OrderTypeEnum type, int quantity) {
        return OrderData.newQueued("o-1", "e-1", "c-1", type, quantity, "hash", NOW.plusSeconds(30), NOW);
    }

    static OrderData queuedStandard() {
        return queued(OrderTypeEnum.STANDARD, TICKETS.size());
    }

    static OrderData reserved() {
        return queuedStandard().withReservation(TICKETS, HOLD_EXPIRES_AT, NOW);
    }

    static OrderData reservedWith(int quantity) {
        return queued(OrderTypeEnum.STANDARD, quantity).withReservation(ticketIds(quantity), HOLD_EXPIRES_AT, NOW);
    }

    static OrderData pendingConfirmation() {
        return reserved().withPendingConfirmation(NOW.plusSeconds(900), NOW);
    }

    static List<String> ticketIds(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(TicketData::ticketIdFor).toList();
    }
}
