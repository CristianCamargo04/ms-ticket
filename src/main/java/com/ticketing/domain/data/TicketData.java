package com.ticketing.domain.data;

import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.TicketStatusEnum;
import java.time.Instant;
import java.util.Objects;

public record TicketData(String eventId, String ticketId, TicketStatusEnum status, String orderId,
                         Instant holdExpiresAt, long version, Instant updatedAt) {
    public static final long INITIAL_VERSION = 1L;

    public static final int MAX_SEQUENCE = 999_999;

    private static final String TICKET_ID_FORMAT = "T-%06d";

    public TicketData {
        DomainPreconditions.requireNonBlank(eventId, "eventId");
        DomainPreconditions.requireNonBlank(ticketId, "ticketId");
        Objects.requireNonNull(status, "status");
        DomainPreconditions.requireNonNegative(version, "version");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static TicketData available(String eventId, String ticketId, Instant now) {
        return new TicketData(eventId, ticketId, TicketStatusEnum.AVAILABLE, null, null, INITIAL_VERSION, now);
    }

    public static String ticketIdFor(int sequence) {
        DomainPreconditions.requireBetween(sequence, 1, MAX_SEQUENCE, "sequence");
        return TICKET_ID_FORMAT.formatted(sequence);
    }

    public TicketData withState(TicketStatusEnum newStatus, String newOrderId, Instant newHoldExpiresAt,
                                Instant now) {
        return new TicketData(eventId, ticketId, newStatus, newOrderId, newHoldExpiresAt, version + 1, now);
    }
}
