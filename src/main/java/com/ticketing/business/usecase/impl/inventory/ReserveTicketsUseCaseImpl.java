package com.ticketing.business.usecase.impl.inventory;

import com.ticketing.business.usecase.ReserveTicketsUseCase;
import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.data.TicketData;
import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.exception.OrderNotFoundException;
import com.ticketing.domain.exception.TicketingException;
import com.ticketing.domain.internal_request.ReserveTicketsRequest;
import com.ticketing.domain.inventory.InventoryWriteOutcome.Applied;
import com.ticketing.domain.inventory.InventoryWriteOutcome.InsufficientInventory;
import com.ticketing.domain.inventory.InventoryWriteOutcome.OrderStateChanged;
import com.ticketing.domain.inventory.InventoryWriteOutcome.StaleTicketSelection;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import com.ticketing.domain.inventory.IssueComplimentaryCommand;
import com.ticketing.domain.inventory.ReserveInventoryCommand;
import com.ticketing.domain.inventory.RetryPolicy;
import com.ticketing.domain.order.OrderPolicy;
import com.ticketing.enums.ErrorCodeEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.persistence.EventPersistence;
import com.ticketing.persistence.InventoryTransactionPersistence;
import com.ticketing.persistence.OrderPersistence;
import com.ticketing.persistence.TicketPersistence;
import com.ticketing.util.ReactiveRetryUtil;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.RetryBackoffSpec;

@Slf4j
@Service
public class ReserveTicketsUseCaseImpl implements ReserveTicketsUseCase {
    static final String ACTOR = "order-worker";

    static final String REASON_EVENT_NOT_ON_SALE = ErrorCodeEnum.EVENT_NOT_ON_SALE.name();

    static final String REASON_SOLD_OUT = ErrorCodeEnum.SOLD_OUT.name();

    static final String FROM_FIRST_TICKET = "";

    private final OrderPersistence orderPersistence;
    private final EventPersistence eventPersistence;
    private final TicketPersistence ticketPersistence;
    private final InventoryTransactionPersistence inventoryTransactionPersistence;
    private final OrderPolicy orderPolicy;
    private final RetryBackoffSpec inventoryRetry;
    private final Clock clock;
    private final RandomGenerator randomGenerator;

    public ReserveTicketsUseCaseImpl(OrderPersistence orderPersistence, EventPersistence eventPersistence,
                                     TicketPersistence ticketPersistence,
                                     InventoryTransactionPersistence inventoryTransactionPersistence,
                                     OrderPolicy orderPolicy, RetryPolicy inventoryRetryPolicy, Clock clock,
                                     RandomGenerator randomGenerator) {
        this.orderPersistence = orderPersistence;
        this.eventPersistence = eventPersistence;
        this.ticketPersistence = ticketPersistence;
        this.inventoryTransactionPersistence = inventoryTransactionPersistence;
        this.orderPolicy = orderPolicy;
        this.inventoryRetry = ReactiveRetryUtil.backoff(inventoryRetryPolicy.maxAttempts(),
                inventoryRetryPolicy.minBackoff(), inventoryRetryPolicy.maxBackoff(), inventoryRetryPolicy.jitter(),
                TicketingException::isRetryable);
        this.clock = clock;
        this.randomGenerator = randomGenerator;
    }

    @Override
    public Mono<OrderData> execute(ReserveTicketsRequest request) {
        String orderId = request.orderId();
        return reload(orderId)
                .flatMap(order -> {
                    if (order.processingStatus() != OrderProcessingStatusEnum.QUEUED) {
                        log.debug("Order {} already processed ({}), duplicate delivery ignored", orderId,
                                order.processingStatus());
                        return Mono.just(order);
                    }
                    return Mono.defer(() -> attempt(order))
                            .doOnError(TicketingException::isRetryable, error -> log.debug(
                                    "Inventory conflict on order {}, retrying: {}", orderId, error.getMessage()))
                            .retryWhen(inventoryRetry);
                });
    }

    private Mono<OrderData> attempt(OrderData order) {
        Instant now = clock.instant();
        return eventPersistence.findById(order.eventId())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(event -> event
                        .filter(EventData::isOnSale)
                        .map(onSale -> reserveFrom(onSale, order, now))
                        .orElseGet(() -> reject(order, REASON_EVENT_NOT_ON_SALE, now)));
    }

    private Mono<OrderData> reserveFrom(EventData event, OrderData order, Instant now) {
        if (event.availableCount() < order.quantity()) {
            return reject(order, REASON_SOLD_OUT, now);
        }
        return selectTickets(event, order)
                .flatMap(ticketIds -> write(order, ticketIds, now))
                .flatMap(outcome -> switch (outcome) {
                    case Applied(OrderData applied) -> {
                        log.info("Order {} holds tickets {} ({})", applied.orderId(), applied.ticketIds(),
                                applied.ticketStatus());
                        yield Mono.just(applied);
                    }
                    case InsufficientInventory _ -> reject(order, REASON_SOLD_OUT, now);
                    case StaleTicketSelection _ -> Mono.error(new InventoryConflictException(
                            "Stale ticket selection for order " + order.orderId()));
                    case OrderStateChanged _ -> reload(order.orderId());
                });
    }

    private Mono<InventoryWriteOutcome> write(OrderData order, List<String> ticketIds, Instant now) {
        return switch (order.type()) {
            case STANDARD -> inventoryTransactionPersistence.reserve(new ReserveInventoryCommand(order, ticketIds,
                    orderPolicy.holdExpiresAt(now), now, ACTOR));
            case COMPLIMENTARY -> inventoryTransactionPersistence.issueComplimentary(
                    new IssueComplimentaryCommand(order, ticketIds, now, ACTOR));
        };
    }

    private Mono<List<String>> selectTickets(EventData event, OrderData order) {
        int quantity = order.quantity();
        int window = (int) Math.min(Integer.MAX_VALUE, (long) quantity * orderPolicy.candidateFactor());
        String start = TicketData.ticketIdFor(randomGenerator.nextInt(event.totalCapacity()) + 1);
        return availableTicketIds(event.eventId(), start, window)
                .flatMap(fromStart -> fromStart.size() >= quantity
                        ? Mono.just(fromStart)
                        : availableTicketIds(event.eventId(), FROM_FIRST_TICKET, window)
                                .map(fromFirst -> union(fromStart, fromFirst)))
                .flatMap(candidates -> candidates.size() < quantity
                        ? Mono.error(new InventoryConflictException("Only " + candidates.size()
                                + " candidate tickets found for order " + order.orderId() + " (index lag)"))
                        : Mono.just(sample(candidates, quantity)));
    }

    private Mono<List<String>> availableTicketIds(String eventId, String fromTicketId, int limit) {
        return ticketPersistence.findAvailable(eventId, fromTicketId, limit)
                .filter(ticket -> ticket.status() == TicketStatusEnum.AVAILABLE)
                .map(TicketData::ticketId)
                .distinct()
                .collectList();
    }

    private static List<String> union(List<String> first, List<String> second) {
        LinkedHashSet<String> union = new LinkedHashSet<>(first);
        union.addAll(second);
        return List.copyOf(union);
    }

    private List<String> sample(List<String> candidates, int size) {
        List<String> pool = new ArrayList<>(candidates);
        for (int i = 0; i < size; i++) {
            Collections.swap(pool, i, i + randomGenerator.nextInt(pool.size() - i));
        }
        return pool.subList(0, size).stream().sorted().toList();
    }

    private Mono<OrderData> reject(OrderData order, String reason, Instant now) {
        return Mono.defer(() -> orderPersistence.transitionProcessingStatus(order.orderId(),
                        OrderProcessingStatusEnum.QUEUED, OrderProcessingStatusEnum.REJECTED, reason, now))
                .doOnNext(rejected -> {
                    if (rejected) {
                        log.info("Order {} rejected: {}", order.orderId(), reason);
                    }
                })
                .then(reload(order.orderId()));
    }

    private Mono<OrderData> reload(String orderId) {
        return Mono.defer(() -> orderPersistence.findById(orderId))
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId)));
    }
}
