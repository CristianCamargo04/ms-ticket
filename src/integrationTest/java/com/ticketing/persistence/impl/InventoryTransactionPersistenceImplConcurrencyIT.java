package com.ticketing.persistence.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.data.TicketData;
import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import com.ticketing.domain.inventory.ReserveInventoryCommand;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.persistence.support.PersistenceTestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Overselling and double assignment are impossible under real contention on DynamoDB Local: only the conditional
 * transactions arbitrate, exactly like in production.
 */
class InventoryTransactionPersistenceImplConcurrencyIT {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final Duration HOLD = Duration.ofMinutes(10);
    private static final int CAPACITY = 50;
    private static final int CONTENDERS = 200;
    private static final int CANDIDATES = 3;

    private static PersistenceTestContext context;

    @BeforeAll
    static void createTables() {
        context = PersistenceTestContext.create();
    }

    @Test
    void shouldReserveExactlyTheCapacityWhenManyMoreOrdersCompete() {
        EventData event = context.onSaleEventWithTickets(CAPACITY);
        List<OrderData> orders = queuedOrders(event, CONTENDERS);

        Map<OrderData, InventoryWriteOutcome> outcomes = Flux.fromIterable(orders)
                .flatMap(order -> reserveWithRetries(event, order).map(outcome -> Map.entry(order, outcome)),
                        CONTENDERS)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .block(TIMEOUT);

        assertThat(outcomes).hasSize(CONTENDERS);
        List<OrderData> winners = outcomes.values().stream()
                .filter(InventoryWriteOutcome.Applied.class::isInstance)
                .map(outcome -> ((InventoryWriteOutcome.Applied) outcome).order())
                .toList();
        assertThat(winners).hasSize(CAPACITY);
        assertThat(outcomes.values()).filteredOn(outcome -> !(outcome instanceof InventoryWriteOutcome.Applied))
                .hasSize(CONTENDERS - CAPACITY)
                .allMatch(InventoryWriteOutcome.InsufficientInventory.class::isInstance);

        List<String> reservedTicketIds = winners.stream().flatMap(order -> order.ticketIds().stream()).toList();
        assertThat(reservedTicketIds).as("every ticket in at most one order").doesNotHaveDuplicates()
                .hasSize(CAPACITY);

        List<TicketData> tickets = context.tickets().findByIds(event.eventId(), allTicketIds()).collectList()
                .block(TIMEOUT);
        Map<String, String> winnerByTicket = winners.stream()
                .collect(Collectors.toMap(order -> order.ticketIds().getFirst(), OrderData::orderId));
        assertThat(tickets).hasSize(CAPACITY).allSatisfy(ticket -> {
            assertThat(ticket.status()).isEqualTo(TicketStatusEnum.RESERVED);
            assertThat(ticket.orderId()).isEqualTo(winnerByTicket.get(ticket.ticketId()));
            assertThat(ticket.version()).isEqualTo(2);
        });
        assertThat(tickets).extracting(TicketData::orderId).doesNotHaveDuplicates();

        EventData counters = context.event(event.eventId());
        assertThat(counters.availableCount()).isZero();
        assertThat(counters.reservedCount()).isEqualTo(CAPACITY);
        assertThat(counters.pendingCount() + counters.soldCount() + counters.complimentaryCount()).isZero();

        Set<String> winnerIds = winners.stream().map(OrderData::orderId).collect(Collectors.toSet());
        assertThat(orders).allSatisfy(order -> {
            OrderData persisted = context.order(order.orderId());
            if (winnerIds.contains(order.orderId())) {
                assertThat(persisted.processingStatus()).isEqualTo(OrderProcessingStatusEnum.ACTIVE);
                assertThat(persisted.ticketStatus()).isEqualTo(TicketStatusEnum.RESERVED);
                assertThat(persisted.ticketIds()).hasSize(1);
            } else {
                assertThat(persisted.processingStatus()).isEqualTo(OrderProcessingStatusEnum.QUEUED);
                assertThat(persisted.ticketIds()).isEmpty();
            }
        });
        assertThat(Flux.fromIterable(allTicketIds())
                .flatMap(ticketId -> context.transitions().findByTicket(event.eventId(), ticketId).count())
                .collectList().block(TIMEOUT))
                .as("exactly one audit record per reserved ticket").containsOnly(1L);
    }

    @Test
    void shouldLetExactlyOneOfTwoOrdersWinTheSameTicket() {
        int rounds = 20;
        // One spare ticket keeps the event counter condition true, so every loser is a pure ticket conflict.
        EventData event = context.onSaleEventWithTickets(rounds + 1);

        for (int round = 1; round <= rounds; round++) {
            String ticketId = TicketData.ticketIdFor(round);
            List<OrderData> rivals = queuedOrders(event, 2);
            Instant now = PersistenceTestContext.now();

            List<Object> results = Flux.fromIterable(rivals)
                    .flatMap(order -> context.inventory()
                            .reserve(new ReserveInventoryCommand(order, List.of(ticketId), now.plus(HOLD), now, "it"))
                            .<Object>map(Function.identity())
                            .onErrorResume(InventoryConflictException.class, Mono::just), 2)
                    .collectList()
                    .block(TIMEOUT);

            assertThat(results).filteredOn(InventoryWriteOutcome.Applied.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(result -> !(result instanceof InventoryWriteOutcome.Applied))
                    .singleElement()
                    .satisfies(loser -> assertThat(loser).isInstanceOfAny(
                            InventoryWriteOutcome.StaleTicketSelection.class, InventoryConflictException.class));
            OrderData winner = results.stream()
                    .filter(InventoryWriteOutcome.Applied.class::isInstance)
                    .map(result -> ((InventoryWriteOutcome.Applied) result).order())
                    .findFirst()
                    .orElseThrow();
            OrderData loser = rivals.stream().filter(order -> !order.orderId().equals(winner.orderId()))
                    .findFirst().orElseThrow();
            assertThat(context.tickets().findByIds(event.eventId(), List.of(ticketId)).blockFirst(TIMEOUT))
                    .extracting(TicketData::orderId).isEqualTo(winner.orderId());
            Instant retryAt = PersistenceTestContext.now().plusMillis(1);
            assertThat(context.inventory().reserve(new ReserveInventoryCommand(loser, List.of(ticketId),
                    retryAt.plus(HOLD), retryAt, "it")).block(TIMEOUT))
                    .as("a retry of the loser on the same ticket")
                    .isInstanceOf(InventoryWriteOutcome.StaleTicketSelection.class);
        }

        EventData counters = context.event(event.eventId());
        assertThat(counters.availableCount()).isEqualTo(1);
        assertThat(counters.reservedCount()).isEqualTo(rounds);
    }

    /**
     * What the reservation worker does: read a few candidates from the (eventually consistent) availability index
     * starting at a random ticket, pick one at random and let the transaction arbitrate; a stale selection or a
     * transaction conflict is retried with backoff.
     */
    private static Mono<InventoryWriteOutcome> reserveWithRetries(EventData event, OrderData order) {
        return Mono.defer(() -> {
                    Instant now = PersistenceTestContext.now();
                    String start = TicketData.ticketIdFor(ThreadLocalRandom.current().nextInt(CAPACITY) + 1);
                    return context.tickets().findAvailable(event.eventId(), start, CANDIDATES)
                            .switchIfEmpty(context.tickets().findAvailable(event.eventId(), "", CANDIDATES))
                            .collectList()
                            .flatMap(candidates -> candidates.isEmpty()
                                    ? Mono.<InventoryWriteOutcome>just(new InventoryWriteOutcome.InsufficientInventory())
                                    : context.inventory().reserve(new ReserveInventoryCommand(order,
                                            List.of(candidates.get(ThreadLocalRandom.current()
                                                    .nextInt(candidates.size())).ticketId()),
                                            now.plus(HOLD), now, "it")));
                })
                .flatMap(outcome -> outcome instanceof InventoryWriteOutcome.StaleTicketSelection
                        ? Mono.<InventoryWriteOutcome>error(new InventoryConflictException("stale ticket selection"))
                        : Mono.just(outcome))
                .retryWhen(Retry.backoff(200, Duration.ofMillis(5)).maxBackoff(Duration.ofMillis(200))
                        .filter(InventoryConflictException.class::isInstance));
    }

    private static List<OrderData> queuedOrders(EventData event, int count) {
        Instant now = PersistenceTestContext.now();
        return Flux.range(0, count)
                .map(index -> OrderData.newQueued(UUID.randomUUID().toString(), event.eventId(), "customer-" + index,
                        OrderTypeEnum.STANDARD, 1, "hash-" + index, now.plusSeconds(30), now))
                .flatMap(order -> context.orders().insertIfAbsent(order).thenReturn(order), 32)
                .collectList()
                .block(TIMEOUT);
    }

    private static List<String> allTicketIds() {
        return IntStream.rangeClosed(1, CAPACITY).mapToObj(TicketData::ticketIdFor).toList();
    }
}
