package com.ticketing.business.usecase.impl.order;

import com.ticketing.business.usecase.ConfirmOrderUseCase;
import com.ticketing.client.sdk.SqsOrderQueueClient;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.exception.InvalidOrderStateException;
import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.exception.OrderNotFoundException;
import com.ticketing.domain.exception.ReservationExpiredException;
import com.ticketing.domain.exception.TicketingException;
import com.ticketing.domain.internal_request.ConfirmOrderRequest;
import com.ticketing.domain.internal_request.PaymentRequestedMessage;
import com.ticketing.domain.inventory.ConfirmInventoryCommand;
import com.ticketing.domain.inventory.InventoryWriteOutcome.Applied;
import com.ticketing.domain.inventory.InventoryWriteOutcome.InsufficientInventory;
import com.ticketing.domain.inventory.InventoryWriteOutcome.OrderStateChanged;
import com.ticketing.domain.inventory.InventoryWriteOutcome.StaleTicketSelection;
import com.ticketing.domain.inventory.RetryPolicy;
import com.ticketing.domain.order.OrderPolicy;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.persistence.InventoryTransactionPersistence;
import com.ticketing.persistence.OrderPersistence;
import com.ticketing.util.ReactiveRetryUtil;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.RetryBackoffSpec;

/**
 * Confirms a reservation: moves the reserved tickets to {@code PENDING_CONFIRMATION} in one conditional transaction
 * (hold still valid, caller owns the order) and publishes the payment message.
 *
 * <p>Idempotent: an order already pending confirmation republishes its payment message (the gateway charge is
 * idempotent on the order id) and a sold order is returned as is. An order whose hold was released by the expiration
 * sweeper (processing status {@code EXPIRED}) is reported as {@link ReservationExpiredException}, also when the hold
 * instant has not passed yet on this instance's clock. When the transaction loses a race (concurrent confirmation, hold
 * expiration sweeper, cancellation) the order is reloaded to report the winner's outcome. A payment
 * message that cannot be published is only logged: the order stays pending, the customer may confirm again and the
 * payment reconciliation sweeper settles it after its confirmation deadline.
 */
@Service
public class ConfirmOrderUseCaseImpl implements ConfirmOrderUseCase {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfirmOrderUseCaseImpl.class);

    private final OrderPersistence orderPersistence;
    private final InventoryTransactionPersistence inventoryTransactionPersistence;
    private final SqsOrderQueueClient sqsOrderQueueClient;
    private final OrderPolicy orderPolicy;
    private final RetryBackoffSpec publishRetry;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param orderPersistence                orders table
     * @param inventoryTransactionPersistence inventory transactions
     * @param sqsOrderQueueClient             order queues
     * @param orderPolicy                     order rules (confirmation deadline)
     * @param publishRetryPolicy              retries of the payment message publication
     * @param clock                           time source
     */
    public ConfirmOrderUseCaseImpl(OrderPersistence orderPersistence,
                                   InventoryTransactionPersistence inventoryTransactionPersistence,
                                   SqsOrderQueueClient sqsOrderQueueClient, OrderPolicy orderPolicy,
                                   RetryPolicy publishRetryPolicy, Clock clock) {
        this.orderPersistence = orderPersistence;
        this.inventoryTransactionPersistence = inventoryTransactionPersistence;
        this.sqsOrderQueueClient = sqsOrderQueueClient;
        this.orderPolicy = orderPolicy;
        this.publishRetry = ReactiveRetryUtil.backoff(publishRetryPolicy.maxAttempts(),
                publishRetryPolicy.minBackoff(), publishRetryPolicy.maxBackoff(), publishRetryPolicy.jitter(),
                TicketingException::isRetryable);
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<OrderData> execute(ConfirmOrderRequest request) {
        return Mono.defer(() -> orderPersistence.findById(request.orderId()))
                .filter(order -> order.isOwnedBy(request.customerId()))
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(request.orderId())))
                .flatMap(order -> switch (order.ticketStatus()) {
                    case PENDING_CONFIRMATION -> publishPayment(order, request).thenReturn(order);
                    case SOLD -> Mono.just(order);
                    case AVAILABLE, RESERVED, COMPLIMENTARY -> confirmHold(order, request);
                });
    }

    private Mono<OrderData> confirmHold(OrderData order, ConfirmOrderRequest request) {
        if (order.processingStatus() == OrderProcessingStatusEnum.EXPIRED) {
            return Mono.error(new ReservationExpiredException(order.orderId()));
        }
        if (order.processingStatus() != OrderProcessingStatusEnum.ACTIVE
                || order.ticketStatus() != TicketStatusEnum.RESERVED) {
            return Mono.error(new InvalidOrderStateException(order.orderId(), "order is " + order.processingStatus()
                    + " with tickets " + order.ticketStatus() + " and cannot be confirmed"));
        }
        Instant now = clock.instant();
        if (order.isHoldExpired(now)) {
            return Mono.error(new ReservationExpiredException(order.orderId()));
        }
        return Mono.defer(() -> inventoryTransactionPersistence.confirm(new ConfirmInventoryCommand(order,
                        orderPolicy.confirmationDeadline(now), now, request.customerId())))
                .flatMap(outcome -> switch (outcome) {
                    case Applied(OrderData confirmed) -> {
                        LOGGER.info("Order {} confirmed, payment requested", confirmed.orderId());
                        yield publishPayment(confirmed, request).thenReturn(confirmed);
                    }
                    case OrderStateChanged _ -> reload(order.orderId())
                            .flatMap(current -> afterLostRace(current, now));
                    case InsufficientInventory _ -> ticketsChanged(order);
                    case StaleTicketSelection _ -> ticketsChanged(order);
                });
    }

    private static Mono<OrderData> ticketsChanged(OrderData order) {
        return Mono.error(new InventoryConflictException(
                "Tickets of order " + order.orderId() + " changed during the confirmation"));
    }

    private static Mono<OrderData> afterLostRace(OrderData current, Instant now) {
        if (current.ticketStatus() == TicketStatusEnum.PENDING_CONFIRMATION
                || current.ticketStatus() == TicketStatusEnum.SOLD) {
            return Mono.just(current);
        }
        if (current.processingStatus() == OrderProcessingStatusEnum.EXPIRED || current.isHoldExpired(now)) {
            return Mono.error(new ReservationExpiredException(current.orderId()));
        }
        return Mono.error(new InvalidOrderStateException(current.orderId(), "order is "
                + current.processingStatus() + " with tickets " + current.ticketStatus() + " and cannot be confirmed"));
    }

    private Mono<OrderData> reload(String orderId) {
        return Mono.defer(() -> orderPersistence.findById(orderId))
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId)));
    }

    private Mono<Void> publishPayment(OrderData order, ConfirmOrderRequest request) {
        return Mono.defer(() -> sqsOrderQueueClient.publishPaymentRequested(new PaymentRequestedMessage(
                        order.orderId(), request.paymentToken(), correlationIdOrOrderId(request.correlationId(), order),
                        clock.instant())))
                .retryWhen(publishRetry)
                .doOnNext(messageId -> LOGGER.debug("Payment message {} published for order {}", messageId,
                        order.orderId()))
                .then()
                .onErrorResume(error -> {
                    LOGGER.warn("Payment message of order {} not published, it stays pending confirmation until a"
                            + " new confirmation or the payment reconciliation: {}", order.orderId(), error.toString());
                    return Mono.empty();
                });
    }

    private static String correlationIdOrOrderId(String correlationId, OrderData order) {
        return correlationId == null || correlationId.isBlank() ? order.orderId() : correlationId;
    }
}
