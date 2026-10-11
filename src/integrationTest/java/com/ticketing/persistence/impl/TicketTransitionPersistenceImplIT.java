package com.ticketing.persistence.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.data.TicketTransitionData;
import com.ticketing.domain.inventory.ConfirmInventoryCommand;
import com.ticketing.domain.inventory.FinalizeSaleCommand;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import com.ticketing.domain.inventory.ReserveInventoryCommand;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import com.ticketing.persistence.support.PersistenceTestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TicketTransitionPersistenceImplIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static PersistenceTestContext context;

    @BeforeAll
    static void createTables() {
        context = PersistenceTestContext.create();
    }

    @Test
    void shouldReturnTheHistoryOfATicketInChronologicalOrder() {
        EventData event = context.onSaleEventWithTickets(2);
        OrderData queued = context.queuedOrder(event.eventId(), OrderTypeEnum.STANDARD, 1);
        Instant reservedAt = PersistenceTestContext.now();
        Instant confirmedAt = reservedAt.plusSeconds(5);
        Instant soldAt = reservedAt.plusSeconds(9);

        OrderData reserved = applied(context.inventory().reserve(new ReserveInventoryCommand(queued,
                List.of("T-000002"), reservedAt.plus(Duration.ofMinutes(10)), reservedAt, "order-worker")));
        OrderData pending = applied(context.inventory().confirm(new ConfirmInventoryCommand(reserved,
                confirmedAt.plus(Duration.ofMinutes(5)), confirmedAt, "customer-1")));
        applied(context.inventory().finalizeSale(new FinalizeSaleCommand(pending, "pay-1", soldAt,
                "payment-worker")));

        List<TicketTransitionData> history = context.transitions().findByTicket(event.eventId(), "T-000002")
                .collectList().block(TIMEOUT);

        assertThat(history).extracting(TicketTransitionData::reason).containsExactly(
                TransitionReasonEnum.RESERVATION, TransitionReasonEnum.CUSTOMER_CONFIRMATION,
                TransitionReasonEnum.PAYMENT_APPROVED);
        assertThat(history).extracting(TicketTransitionData::toStatus).containsExactly(
                TicketStatusEnum.RESERVED, TicketStatusEnum.PENDING_CONFIRMATION, TicketStatusEnum.SOLD);
        assertThat(history).extracting(TicketTransitionData::occurredAt)
                .containsExactly(reservedAt, confirmedAt, soldAt);
        assertThat(history).extracting(TicketTransitionData::actor)
                .containsExactly("order-worker", "customer-1", "payment-worker");
        assertThat(history).extracting(TicketTransitionData::transitionId).doesNotHaveDuplicates();
        assertThat(history).allSatisfy(transition -> {
            assertThat(transition.eventId()).isEqualTo(event.eventId());
            assertThat(transition.ticketId()).isEqualTo("T-000002");
            assertThat(transition.orderId()).isEqualTo(queued.orderId());
        });
        StepVerifier.create(context.transitions().findByTicket(event.eventId(), "T-000001")).verifyComplete();
    }

    private static OrderData applied(Mono<InventoryWriteOutcome> outcome) {
        InventoryWriteOutcome result = outcome.block(TIMEOUT);
        assertThat(result).isInstanceOf(InventoryWriteOutcome.Applied.class);
        return ((InventoryWriteOutcome.Applied) result).order();
    }
}
