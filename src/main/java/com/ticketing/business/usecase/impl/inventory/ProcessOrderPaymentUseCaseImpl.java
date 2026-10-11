package com.ticketing.business.usecase.impl.inventory;

import com.ticketing.business.usecase.ProcessOrderPaymentUseCase;
import com.ticketing.client.rest.api.PaymentGatewayChargeApi;
import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.exception.EventNotFoundException;
import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.exception.OrderNotFoundException;
import com.ticketing.domain.exception.PaymentGatewayException;
import com.ticketing.domain.exception.TicketingException;
import com.ticketing.domain.external_request.payment.PaymentChargeRequest;
import com.ticketing.domain.internal_request.ProcessOrderPaymentRequest;
import com.ticketing.domain.inventory.FinalizeSaleCommand;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import com.ticketing.domain.inventory.InventoryWriteOutcome.Applied;
import com.ticketing.domain.inventory.InventoryWriteOutcome.InsufficientInventory;
import com.ticketing.domain.inventory.InventoryWriteOutcome.OrderStateChanged;
import com.ticketing.domain.inventory.InventoryWriteOutcome.StaleTicketSelection;
import com.ticketing.domain.inventory.ReleaseInventoryCommand;
import com.ticketing.domain.inventory.RetryPolicy;
import com.ticketing.domain.order.PaymentOutcome;
import com.ticketing.domain.order.PaymentOutcome.Approved;
import com.ticketing.domain.order.PaymentOutcome.Declined;
import com.ticketing.domain.order.PaymentOutcome.NotFound;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import com.ticketing.persistence.EventPersistence;
import com.ticketing.persistence.InventoryTransactionPersistence;
import com.ticketing.persistence.OrderPersistence;
import com.ticketing.util.ReactiveRetryUtil;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.RetryBackoffSpec;

/**
 * Charges an order pending confirmation and settles it; SQS consumer, idempotent under at-least-once delivery.
 *
 * <p>The charge uses the order id as gateway idempotency key, so redeliveries never charge twice. An approved charge
 * finalizes the sale ({@code PENDING_CONFIRMATION -> SOLD}); a declined one releases the tickets. The settlement write
 * is retried locally on transient conflicts (the money is already captured, so it must not wait for a redelivery);
 * gateway errors propagate so that SQS redelivers the message.
 *
 * <p>No charge is <em>started</em> once the confirmation deadline of the order ({@link OrderData#deadlineAt()}) is
 * due: from that instant the payment reconciliation sweeper owns the order (it looks the charge up and finalizes or
 * releases it), so a message redelivered late, e.g. after a gateway outage, is acknowledged without charging. Only a
 * charge started before the deadline and completing after the sweeper's lookup can still meet a released order: then
 * an ERROR is logged for a manual refund (documented limitation).
 */
@Service
public class ProcessOrderPaymentUseCaseImpl implements ProcessOrderPaymentUseCase {

    /** Actor recorded in the audit trail. */
    static final String ACTOR = "payment-worker";

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessOrderPaymentUseCaseImpl.class);

    private final OrderPersistence orderPersistence;
    private final EventPersistence eventPersistence;
    private final InventoryTransactionPersistence inventoryTransactionPersistence;
    private final PaymentGatewayChargeApi paymentGatewayChargeApi;
    private final RetryBackoffSpec inventoryRetry;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param orderPersistence                orders table
     * @param eventPersistence                events table (ticket price and currency)
     * @param inventoryTransactionPersistence inventory transactions
     * @param paymentGatewayChargeApi         payment gateway
     * @param inventoryRetryPolicy            retries of transient inventory conflicts
     * @param clock                           time source
     */
    public ProcessOrderPaymentUseCaseImpl(OrderPersistence orderPersistence, EventPersistence eventPersistence,
                                          InventoryTransactionPersistence inventoryTransactionPersistence,
                                          PaymentGatewayChargeApi paymentGatewayChargeApi,
                                          RetryPolicy inventoryRetryPolicy, Clock clock) {
        this.orderPersistence = orderPersistence;
        this.eventPersistence = eventPersistence;
        this.inventoryTransactionPersistence = inventoryTransactionPersistence;
        this.paymentGatewayChargeApi = paymentGatewayChargeApi;
        this.inventoryRetry = ReactiveRetryUtil.backoff(inventoryRetryPolicy.maxAttempts(),
                inventoryRetryPolicy.minBackoff(), inventoryRetryPolicy.maxBackoff(), inventoryRetryPolicy.jitter(),
                TicketingException::isRetryable);
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<OrderData> execute(ProcessOrderPaymentRequest request) {
        String orderId = request.orderId();
        return reload(orderId)
                .flatMap(order -> {
                    if (order.ticketStatus() != TicketStatusEnum.PENDING_CONFIRMATION) {
                        LOGGER.debug("Order {} is not pending confirmation ({}), duplicate delivery ignored", orderId,
                                order.ticketStatus());
                        return Mono.just(order);
                    }
                    if (isConfirmationDeadlineDue(order, clock.instant())) {
                        LOGGER.warn("Order {} reached its confirmation deadline before being charged, the payment"
                                + " reconciliation settles it", orderId);
                        return Mono.just(order);
                    }
                    return Mono.defer(() -> eventPersistence.findById(order.eventId()))
                            .switchIfEmpty(Mono.error(() -> new EventNotFoundException(order.eventId())))
                            .flatMap(event -> paymentGatewayChargeApi.charge(chargeOf(order, event, request)))
                            .flatMap(outcome -> settle(order, outcome));
                });
    }

    /**
     * Whether the payment reconciliation sweeper owns the order, i.e. its confirmation deadline is due at {@code now}
     * (same boundary as the deadline index query {@code deadlineAt <= now}).
     */
    private static boolean isConfirmationDeadlineDue(OrderData order, Instant now) {
        return order.deadlineAt() != null && !now.isBefore(order.deadlineAt());
    }

    private static PaymentChargeRequest chargeOf(OrderData order, EventData event, ProcessOrderPaymentRequest request) {
        return new PaymentChargeRequest(order.orderId(), order.orderId(), order.customerId(),
                order.totalAmount(event.ticketPrice()), event.currency(), request.paymentToken());
    }

    private Mono<OrderData> settle(OrderData order, PaymentOutcome payment) {
        Instant now = clock.instant();
        return switch (payment) {
            case Approved(String paymentReference) -> withRetry(() -> inventoryTransactionPersistence.finalizeSale(
                    new FinalizeSaleCommand(order, paymentReference, now, ACTOR)))
                    .flatMap(outcome -> afterWrite(order, outcome, true));
            case Declined _ -> withRetry(() -> inventoryTransactionPersistence.release(new ReleaseInventoryCommand(
                    order, TicketStatusEnum.PENDING_CONFIRMATION, OrderProcessingStatusEnum.PAYMENT_DECLINED,
                    TransitionReasonEnum.PAYMENT_DECLINED, now, ACTOR)))
                    .flatMap(outcome -> afterWrite(order, outcome, false));
            case NotFound _ -> Mono.error(new PaymentGatewayException(
                    "Payment gateway returned no charge result for order " + order.orderId()));
        };
    }

    private Mono<InventoryWriteOutcome> withRetry(Supplier<Mono<InventoryWriteOutcome>> write) {
        return Mono.defer(write).retryWhen(inventoryRetry);
    }

    private Mono<OrderData> afterWrite(OrderData order, InventoryWriteOutcome outcome, boolean approved) {
        return switch (outcome) {
            case Applied(OrderData settled) -> {
                LOGGER.info("Order {} payment {}: tickets {}", settled.orderId(), approved ? "approved" : "declined",
                        settled.ticketStatus());
                yield Mono.just(settled);
            }
            case OrderStateChanged _ -> reload(order.orderId()).doOnNext(current -> {
                if (approved && current.ticketStatus() != TicketStatusEnum.SOLD) {
                    LOGGER.error("Payment captured for released order {} (status {}): manual refund required",
                            current.orderId(), current.processingStatus());
                }
            });
            case InsufficientInventory _ -> ticketsNotPending(order);
            case StaleTicketSelection _ -> ticketsNotPending(order);
        };
    }

    private static Mono<OrderData> ticketsNotPending(OrderData order) {
        return Mono.error(new InventoryConflictException(
                "Tickets of order " + order.orderId() + " are not pending confirmation"));
    }

    private Mono<OrderData> reload(String orderId) {
        return Mono.defer(() -> orderPersistence.findById(orderId))
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId)));
    }
}
