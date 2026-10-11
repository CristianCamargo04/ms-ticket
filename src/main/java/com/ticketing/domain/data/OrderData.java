package com.ticketing.domain.data;

import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * An order for one or more tickets of an event.
 *
 * <p>An order exposes two orthogonal statuses: {@link #ticketStatus()} (the status shared by all of its tickets,
 * {@link TicketStatusEnum#AVAILABLE} while nothing is held) and {@link #processingStatus()}. The {@code with...}
 * methods return copies with a state change applied in memory, mirroring exactly what the corresponding conditional
 * write persists (every change sets {@code updatedAt = now} and increments {@code version}); they do not check the
 * state machine because the persisted write conditions do.
 *
 * @param orderId          order identifier (UUID v5 derived from the requester and the idempotency key)
 * @param eventId          event identifier
 * @param customerId       owner (JWT subject) or complimentary beneficiary
 * @param type             standard purchase or complimentary issue
 * @param quantity         number of tickets, {@code >= 1}
 * @param ticketIds        tickets assigned to the order: empty until assigned, then exactly {@code quantity} ids
 * @param ticketStatus     status of the tickets held by the order
 * @param processingStatus processing status of the order
 * @param reason           machine-readable reason of a rejection/release (e.g. {@code SOLD_OUT}), nullable
 * @param requestHash      fingerprint of the original request (idempotency payload check)
 * @param holdExpiresAt    end of the reservation hold, nullable
 * @param deadlineAt       next deadline of the order (see {@link #deadlineType()}), nullable
 * @param paymentReference payment identifier once the sale is finalized, nullable
 * @param version          item version, {@code >= 0}
 * @param createdAt        creation instant
 * @param updatedAt        last update instant
 */
public record OrderData(String orderId, String eventId, String customerId, OrderTypeEnum type, int quantity,
                        List<String> ticketIds, TicketStatusEnum ticketStatus,
                        OrderProcessingStatusEnum processingStatus, String reason, String requestHash,
                        Instant holdExpiresAt, Instant deadlineAt, String paymentReference, long version,
                        Instant createdAt, Instant updatedAt) {

    /** Version assigned to a newly created order. */
    public static final long INITIAL_VERSION = 1L;

    /**
     * Validates null-safety, {@code quantity >= 1}, a non-negative version and that {@code ticketIds} is either
     * empty or holds exactly {@code quantity} ids. {@code ticketIds} is defensively copied.
     */
    public OrderData {
        DomainPreconditions.requireNonBlank(orderId, "orderId");
        DomainPreconditions.requireNonBlank(eventId, "eventId");
        DomainPreconditions.requireNonBlank(customerId, "customerId");
        Objects.requireNonNull(type, "type");
        DomainPreconditions.requireBetween(quantity, 1, Integer.MAX_VALUE, "quantity");
        ticketIds = List.copyOf(Objects.requireNonNull(ticketIds, "ticketIds"));
        DomainPreconditions.require(ticketIds.isEmpty() || ticketIds.size() == quantity,
                "ticketIds must be empty or contain exactly quantity ids");
        Objects.requireNonNull(ticketStatus, "ticketStatus");
        Objects.requireNonNull(processingStatus, "processingStatus");
        DomainPreconditions.requireNonBlank(requestHash, "requestHash");
        DomainPreconditions.requireNonNegative(version, "version");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * Creates an accepted order waiting for its reservation to be processed asynchronously.
     *
     * @param orderId          order identifier
     * @param eventId          event identifier
     * @param customerId       owner or beneficiary
     * @param type             order type
     * @param quantity         number of tickets
     * @param requestHash      request fingerprint
     * @param dispatchDeadline when the dispatch sweeper must republish the order if it is still queued
     * @param now              creation instant
     * @return a {@link OrderProcessingStatusEnum#QUEUED} order with ticket status
     *         {@link TicketStatusEnum#AVAILABLE}, no tickets and {@code deadlineAt = dispatchDeadline}
     */
    public static OrderData newQueued(String orderId, String eventId, String customerId, OrderTypeEnum type,
                                      int quantity, String requestHash, Instant dispatchDeadline, Instant now) {
        return new OrderData(orderId, eventId, customerId, type, quantity, List.of(), TicketStatusEnum.AVAILABLE,
                OrderProcessingStatusEnum.QUEUED, null, requestHash, null, dispatchDeadline, null,
                INITIAL_VERSION, now, now);
    }

    /**
     * Ownership check used for anti-IDOR authorization.
     *
     * @param candidateCustomerId the authenticated subject, may be {@code null}
     * @return {@code true} when the order belongs to {@code candidateCustomerId}
     */
    public boolean isOwnedBy(String candidateCustomerId) {
        return customerId.equals(candidateCustomerId);
    }

    /**
     * Whether the reservation hold is over at {@code now} (the expiry instant itself counts as expired).
     *
     * @param now the reference instant
     * @return {@code true} when {@code holdExpiresAt != null && !now.isBefore(holdExpiresAt)}
     */
    public boolean isHoldExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        return holdExpiresAt != null && !now.isBefore(holdExpiresAt);
    }

    /**
     * Idempotency payload check.
     *
     * @param candidateRequestHash fingerprint of the incoming request, may be {@code null}
     * @return {@code true} when the incoming request is the same as the one that created the order
     */
    public boolean matchesRequest(String candidateRequestHash) {
        return requestHash.equals(candidateRequestHash);
    }

    /**
     * The future action this order is waiting for.
     *
     * @return {@link DeadlineTypeEnum#DISPATCH} when queued, {@link DeadlineTypeEnum#HOLD_EXPIRATION} when active and
     *         reserved, {@link DeadlineTypeEnum#PAYMENT_CONFIRMATION} when active and pending confirmation,
     *         otherwise {@code null}
     */
    public DeadlineTypeEnum deadlineType() {
        return switch (processingStatus) {
            case QUEUED -> DeadlineTypeEnum.DISPATCH;
            case ACTIVE -> switch (ticketStatus) {
                case RESERVED -> DeadlineTypeEnum.HOLD_EXPIRATION;
                case PENDING_CONFIRMATION -> DeadlineTypeEnum.PAYMENT_CONFIRMATION;
                case AVAILABLE, SOLD, COMPLIMENTARY -> null;
            };
            case COMPLETED, REJECTED, EXPIRED, CANCELLED, PAYMENT_DECLINED, FAILED -> null;
        };
    }

    /**
     * Amount to charge for the order.
     *
     * @param unitPrice price of one ticket
     * @return {@code unitPrice * quantity}
     */
    public BigDecimal totalAmount(BigDecimal unitPrice) {
        return Objects.requireNonNull(unitPrice, "unitPrice").multiply(BigDecimal.valueOf(quantity));
    }

    /**
     * Generic status change; the deadline is cleared when the new processing status is terminal and kept otherwise.
     *
     * @param newTicketStatus     the new ticket status
     * @param newProcessingStatus the new processing status
     * @param now                 update instant
     * @return the updated copy
     */
    public OrderData withState(TicketStatusEnum newTicketStatus, OrderProcessingStatusEnum newProcessingStatus,
                               Instant now) {
        return copy(ticketIds, newTicketStatus, newProcessingStatus, reason, holdExpiresAt,
                deadlineAfter(newProcessingStatus), paymentReference, now);
    }

    /**
     * Applies a successful reservation ({@code reserve} transaction): tickets
     * {@link TicketStatusEnum#RESERVED}, order {@link OrderProcessingStatusEnum#ACTIVE}, hold and
     * {@link DeadlineTypeEnum#HOLD_EXPIRATION} deadline at {@code holdExpiresAt}.
     *
     * @param newTicketIds     the reserved tickets ({@code quantity} ids)
     * @param newHoldExpiresAt end of the hold
     * @param now              update instant
     * @return the updated copy
     */
    public OrderData withReservation(List<String> newTicketIds, Instant newHoldExpiresAt, Instant now) {
        Objects.requireNonNull(newHoldExpiresAt, "holdExpiresAt");
        return copy(newTicketIds, TicketStatusEnum.RESERVED, OrderProcessingStatusEnum.ACTIVE, reason,
                newHoldExpiresAt, newHoldExpiresAt, paymentReference, now);
    }

    /**
     * Applies a successful complimentary issue ({@code issueComplimentary} transaction): tickets
     * {@link TicketStatusEnum#COMPLIMENTARY}, order {@link OrderProcessingStatusEnum#COMPLETED}, no deadline.
     *
     * @param newTicketIds the issued tickets ({@code quantity} ids)
     * @param now          update instant
     * @return the updated copy
     */
    public OrderData withComplimentaryIssued(List<String> newTicketIds, Instant now) {
        return copy(newTicketIds, TicketStatusEnum.COMPLIMENTARY, OrderProcessingStatusEnum.COMPLETED, reason,
                holdExpiresAt, null, paymentReference, now);
    }

    /**
     * Applies a successful customer confirmation ({@code confirm} transaction): tickets
     * {@link TicketStatusEnum#PENDING_CONFIRMATION} with a {@link DeadlineTypeEnum#PAYMENT_CONFIRMATION} deadline;
     * the processing status is unchanged ({@link OrderProcessingStatusEnum#ACTIVE}).
     *
     * @param confirmationDeadline when the payment must be reconciled at the latest
     * @param now                  update instant
     * @return the updated copy
     */
    public OrderData withPendingConfirmation(Instant confirmationDeadline, Instant now) {
        Objects.requireNonNull(confirmationDeadline, "confirmationDeadline");
        return copy(ticketIds, TicketStatusEnum.PENDING_CONFIRMATION, processingStatus, reason, holdExpiresAt,
                confirmationDeadline, paymentReference, now);
    }

    /**
     * Applies a finalized sale ({@code finalizeSale} transaction): tickets {@link TicketStatusEnum#SOLD}, order
     * {@link OrderProcessingStatusEnum#COMPLETED}, payment reference recorded, no deadline.
     *
     * @param newPaymentReference the payment identifier
     * @param now                 update instant
     * @return the updated copy
     */
    public OrderData withSale(String newPaymentReference, Instant now) {
        Objects.requireNonNull(newPaymentReference, "paymentReference");
        return copy(ticketIds, TicketStatusEnum.SOLD, OrderProcessingStatusEnum.COMPLETED, reason, holdExpiresAt,
                null, newPaymentReference, now);
    }

    /**
     * Applies a release of the held tickets ({@code release} transaction): ticket status back to
     * {@link TicketStatusEnum#AVAILABLE}, terminal processing status, reason recorded, no deadline. The ticket ids
     * and the hold instant are kept for traceability.
     *
     * @param resultingStatus the terminal processing status (e.g. EXPIRED, CANCELLED, PAYMENT_DECLINED)
     * @param newReason       the release reason (typically a {@code TransitionReasonEnum} name)
     * @param now             update instant
     * @return the updated copy
     */
    public OrderData withRelease(OrderProcessingStatusEnum resultingStatus, String newReason, Instant now) {
        return copy(ticketIds, TicketStatusEnum.AVAILABLE, resultingStatus, newReason, holdExpiresAt, null,
                paymentReference, now);
    }

    /**
     * Applies a processing status change without inventory impact ({@code transitionProcessingStatus}, e.g.
     * {@code QUEUED -> REJECTED} or {@code QUEUED -> CANCELLED}); the deadline is cleared when the new status is
     * terminal and kept otherwise.
     *
     * @param newProcessingStatus the new processing status
     * @param newReason           the reason, nullable
     * @param now                 update instant
     * @return the updated copy
     */
    public OrderData withProcessingStatus(OrderProcessingStatusEnum newProcessingStatus, String newReason,
                                          Instant now) {
        return copy(ticketIds, ticketStatus, newProcessingStatus, newReason, holdExpiresAt,
                deadlineAfter(newProcessingStatus), paymentReference, now);
    }

    /**
     * Applies a deadline reschedule ({@code rescheduleDeadline}, e.g. after republishing a queued order).
     *
     * @param newDeadlineAt the new deadline, nullable
     * @param now           update instant
     * @return the updated copy
     */
    public OrderData withDeadline(Instant newDeadlineAt, Instant now) {
        return copy(ticketIds, ticketStatus, processingStatus, reason, holdExpiresAt, newDeadlineAt,
                paymentReference, now);
    }

    private Instant deadlineAfter(OrderProcessingStatusEnum newProcessingStatus) {
        return Objects.requireNonNull(newProcessingStatus, "processingStatus").isTerminal() ? null : deadlineAt;
    }

    private OrderData copy(List<String> newTicketIds, TicketStatusEnum newTicketStatus,
                           OrderProcessingStatusEnum newProcessingStatus, String newReason, Instant newHoldExpiresAt,
                           Instant newDeadlineAt, String newPaymentReference, Instant now) {
        return new OrderData(orderId, eventId, customerId, type, quantity, newTicketIds, newTicketStatus,
                newProcessingStatus, newReason, requestHash, newHoldExpiresAt, newDeadlineAt, newPaymentReference,
                version + 1, createdAt, now);
    }
}
