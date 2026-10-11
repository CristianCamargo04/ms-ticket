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

public record OrderData(String orderId, String eventId, String customerId, OrderTypeEnum type, int quantity,
                        List<String> ticketIds, TicketStatusEnum ticketStatus,
                        OrderProcessingStatusEnum processingStatus, String reason, String requestHash,
                        Instant holdExpiresAt, Instant deadlineAt, String paymentReference, long version,
                        Instant createdAt, Instant updatedAt) {
    public static final long INITIAL_VERSION = 1L;

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

    public static OrderData newQueued(String orderId, String eventId, String customerId, OrderTypeEnum type,
                                      int quantity, String requestHash, Instant dispatchDeadline, Instant now) {
        return new OrderData(orderId, eventId, customerId, type, quantity, List.of(), TicketStatusEnum.AVAILABLE,
                OrderProcessingStatusEnum.QUEUED, null, requestHash, null, dispatchDeadline, null,
                INITIAL_VERSION, now, now);
    }

    public boolean isOwnedBy(String candidateCustomerId) {
        return customerId.equals(candidateCustomerId);
    }

    public boolean isHoldExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        return holdExpiresAt != null && !now.isBefore(holdExpiresAt);
    }

    public boolean matchesRequest(String candidateRequestHash) {
        return requestHash.equals(candidateRequestHash);
    }

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

    public BigDecimal totalAmount(BigDecimal unitPrice) {
        return Objects.requireNonNull(unitPrice, "unitPrice").multiply(BigDecimal.valueOf(quantity));
    }

    public OrderData withState(TicketStatusEnum newTicketStatus, OrderProcessingStatusEnum newProcessingStatus,
                               Instant now) {
        return copy(ticketIds, newTicketStatus, newProcessingStatus, reason, holdExpiresAt,
                deadlineAfter(newProcessingStatus), paymentReference, now);
    }

    public OrderData withReservation(List<String> newTicketIds, Instant newHoldExpiresAt, Instant now) {
        Objects.requireNonNull(newHoldExpiresAt, "holdExpiresAt");
        return copy(newTicketIds, TicketStatusEnum.RESERVED, OrderProcessingStatusEnum.ACTIVE, reason,
                newHoldExpiresAt, newHoldExpiresAt, paymentReference, now);
    }

    public OrderData withComplimentaryIssued(List<String> newTicketIds, Instant now) {
        return copy(newTicketIds, TicketStatusEnum.COMPLIMENTARY, OrderProcessingStatusEnum.COMPLETED, reason,
                holdExpiresAt, null, paymentReference, now);
    }

    public OrderData withPendingConfirmation(Instant confirmationDeadline, Instant now) {
        Objects.requireNonNull(confirmationDeadline, "confirmationDeadline");
        return copy(ticketIds, TicketStatusEnum.PENDING_CONFIRMATION, processingStatus, reason, holdExpiresAt,
                confirmationDeadline, paymentReference, now);
    }

    public OrderData withSale(String newPaymentReference, Instant now) {
        Objects.requireNonNull(newPaymentReference, "paymentReference");
        return copy(ticketIds, TicketStatusEnum.SOLD, OrderProcessingStatusEnum.COMPLETED, reason, holdExpiresAt,
                null, newPaymentReference, now);
    }

    public OrderData withRelease(OrderProcessingStatusEnum resultingStatus, String newReason, Instant now) {
        return copy(ticketIds, TicketStatusEnum.AVAILABLE, resultingStatus, newReason, holdExpiresAt, null,
                paymentReference, now);
    }

    public OrderData withProcessingStatus(OrderProcessingStatusEnum newProcessingStatus, String newReason,
                                          Instant now) {
        return copy(ticketIds, ticketStatus, newProcessingStatus, newReason, holdExpiresAt,
                deadlineAfter(newProcessingStatus), paymentReference, now);
    }

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
