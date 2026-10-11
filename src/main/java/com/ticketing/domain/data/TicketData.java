package com.ticketing.domain.data;

import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.TicketStatusEnum;
import java.time.Instant;
import java.util.Objects;

/**
 * A single ticket of an event.
 *
 * @param eventId       owning event identifier
 * @param ticketId      ticket identifier, see {@link #ticketIdFor(int)}
 * @param status        current status
 * @param orderId       order holding the ticket, {@code null} while {@link TicketStatusEnum#AVAILABLE}
 * @param holdExpiresAt end of the reservation hold, {@code null} when not held
 * @param version       item version, {@code >= 0}
 * @param updatedAt     last update instant
 */
public record TicketData(String eventId, String ticketId, TicketStatusEnum status, String orderId,
                         Instant holdExpiresAt, long version, Instant updatedAt) {

    /** Version assigned to a newly generated ticket. */
    public static final long INITIAL_VERSION = 1L;

    /** Highest sequence representable by the six-digit ticket id format. */
    public static final int MAX_SEQUENCE = 999_999;

    private static final String TICKET_ID_FORMAT = "T-%06d";

    /**
     * Validates null-safety and a non-negative version.
     */
    public TicketData {
        DomainPreconditions.requireNonBlank(eventId, "eventId");
        DomainPreconditions.requireNonBlank(ticketId, "ticketId");
        Objects.requireNonNull(status, "status");
        DomainPreconditions.requireNonNegative(version, "version");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * Creates a freshly generated, available ticket.
     *
     * @param eventId  owning event identifier
     * @param ticketId ticket identifier
     * @param now      creation instant
     * @return an {@link TicketStatusEnum#AVAILABLE} ticket without order nor hold, version {@link #INITIAL_VERSION}
     */
    public static TicketData available(String eventId, String ticketId, Instant now) {
        return new TicketData(eventId, ticketId, TicketStatusEnum.AVAILABLE, null, null, INITIAL_VERSION, now);
    }

    /**
     * Formats the identifier of the n-th ticket of an event as {@code T-%06d}; zero padding keeps the
     * lexicographic order of the ids equal to their numeric order (required by the availability index range scan).
     *
     * @param sequence 1-based sequence, between 1 and {@link #MAX_SEQUENCE}
     * @return the ticket id, e.g. {@code T-000042}
     * @throws IllegalArgumentException when the sequence is out of range
     */
    public static String ticketIdFor(int sequence) {
        DomainPreconditions.requireBetween(sequence, 1, MAX_SEQUENCE, "sequence");
        return TICKET_ID_FORMAT.formatted(sequence);
    }

    /**
     * Returns a copy in another state, mirroring in memory what an inventory transaction persisted.
     *
     * @param newStatus        the new status
     * @param newOrderId       the holding order, {@code null} when released
     * @param newHoldExpiresAt the hold end, {@code null} when not held
     * @param now              update instant
     * @return a copy with the given state, {@code updatedAt = now} and {@code version + 1}
     */
    public TicketData withState(TicketStatusEnum newStatus, String newOrderId, Instant newHoldExpiresAt,
                                Instant now) {
        return new TicketData(eventId, ticketId, newStatus, newOrderId, newHoldExpiresAt, version + 1, now);
    }
}
