package com.ticketing.domain.data;

import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.EventStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * An event with its inventory counters.
 *
 * <p>The counters partition the capacity: {@code availableCount + reservedCount + pendingCount + soldCount +
 * complimentaryCount == totalCapacity} is maintained by the inventory transactions. The record does not enforce the
 * sum so that a corrupted item can still be read and repaired; it only enforces non-negative values.
 *
 * @param eventId            event identifier (UUID)
 * @param name               display name
 * @param venue              venue name
 * @param eventDate          when the event takes place
 * @param totalCapacity      number of pre-generated tickets
 * @param ticketPrice        unit price, {@code >= 0}
 * @param currency           ISO-4217 currency code
 * @param status             lifecycle status
 * @param availableCount     tickets that can still be reserved
 * @param reservedCount      tickets held by reservations
 * @param pendingCount       tickets awaiting payment confirmation
 * @param soldCount          tickets sold
 * @param complimentaryCount complimentary tickets issued
 * @param version            item version, {@code >= 0}
 * @param createdAt          creation instant
 * @param updatedAt          last update instant
 */
public record EventData(String eventId, String name, String venue, Instant eventDate, int totalCapacity,
                        BigDecimal ticketPrice, String currency, EventStatusEnum status,
                        int availableCount, int reservedCount, int pendingCount, int soldCount,
                        int complimentaryCount, long version, Instant createdAt, Instant updatedAt) {

    /** Version assigned to a newly created event. */
    public static final long INITIAL_VERSION = 1L;

    /**
     * Validates null-safety and non-negative counters, version and price.
     */
    public EventData {
        DomainPreconditions.requireNonBlank(eventId, "eventId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(venue, "venue");
        Objects.requireNonNull(eventDate, "eventDate");
        DomainPreconditions.requireNonNegative(totalCapacity, "totalCapacity");
        DomainPreconditions.requireNonNegative(ticketPrice, "ticketPrice");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(status, "status");
        DomainPreconditions.requireNonNegative(availableCount, "availableCount");
        DomainPreconditions.requireNonNegative(reservedCount, "reservedCount");
        DomainPreconditions.requireNonNegative(pendingCount, "pendingCount");
        DomainPreconditions.requireNonNegative(soldCount, "soldCount");
        DomainPreconditions.requireNonNegative(complimentaryCount, "complimentaryCount");
        DomainPreconditions.requireNonNegative(version, "version");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * Creates a new event in {@link EventStatusEnum#PUBLISHING} status whose whole capacity is available.
     *
     * @param eventId   event identifier
     * @param name      display name
     * @param venue     venue name
     * @param eventDate when the event takes place
     * @param capacity  number of tickets
     * @param price     unit price
     * @param currency  ISO-4217 currency code
     * @param now       creation instant (also used as {@code updatedAt})
     * @return the new event with {@code availableCount = capacity}, other counters at zero and version
     *         {@link #INITIAL_VERSION}
     */
    public static EventData newPublishing(String eventId, String name, String venue, Instant eventDate, int capacity,
                                          BigDecimal price, String currency, Instant now) {
        return new EventData(eventId, name, venue, eventDate, capacity, price, currency, EventStatusEnum.PUBLISHING,
                capacity, 0, 0, 0, 0, INITIAL_VERSION, now, now);
    }

    /**
     * Builds the public availability view of the counters.
     *
     * @param capturedAt when the counters were read
     * @return the snapshot
     */
    public AvailabilitySnapshot toAvailabilitySnapshot(Instant capturedAt) {
        return new AvailabilitySnapshot(eventId, totalCapacity, availableCount, reservedCount, pendingCount,
                soldCount, complimentaryCount, capturedAt);
    }

    /**
     * Whether new orders are accepted.
     *
     * @return {@code true} when the status is {@link EventStatusEnum#ON_SALE}
     */
    public boolean isOnSale() {
        return status == EventStatusEnum.ON_SALE;
    }

    /**
     * Returns a copy with another status, as persisted by a status update.
     *
     * @param newStatus the new status
     * @param now       update instant
     * @return a copy with {@code status = newStatus}, {@code updatedAt = now} and {@code version + 1}
     */
    public EventData withStatus(EventStatusEnum newStatus, Instant now) {
        return new EventData(eventId, name, venue, eventDate, totalCapacity, ticketPrice, currency, newStatus,
                availableCount, reservedCount, pendingCount, soldCount, complimentaryCount, version + 1,
                createdAt, now);
    }

    /**
     * Returns a copy with new inventory counters, mirroring in memory what an inventory transaction persisted
     * (useful for in-memory fakes and for returning the updated state without re-reading it).
     *
     * @param newAvailableCount     tickets that can still be reserved
     * @param newReservedCount      tickets held by reservations
     * @param newPendingCount       tickets awaiting payment confirmation
     * @param newSoldCount          tickets sold
     * @param newComplimentaryCount complimentary tickets issued
     * @param now                   update instant
     * @return a copy with the given counters, {@code updatedAt = now} and {@code version + 1}
     */
    public EventData withCounters(int newAvailableCount, int newReservedCount, int newPendingCount, int newSoldCount,
                                  int newComplimentaryCount, Instant now) {
        return new EventData(eventId, name, venue, eventDate, totalCapacity, ticketPrice, currency, status,
                newAvailableCount, newReservedCount, newPendingCount, newSoldCount, newComplimentaryCount,
                version + 1, createdAt, now);
    }
}
