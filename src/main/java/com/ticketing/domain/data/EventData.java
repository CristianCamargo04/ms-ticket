package com.ticketing.domain.data;

import com.ticketing.domain.validation.DomainPreconditions;
import com.ticketing.enums.EventStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record EventData(String eventId, String name, String venue, Instant eventDate, int totalCapacity,
                        BigDecimal ticketPrice, String currency, EventStatusEnum status,
                        int availableCount, int reservedCount, int pendingCount, int soldCount,
                        int complimentaryCount, long version, Instant createdAt, Instant updatedAt) {
    public static final long INITIAL_VERSION = 1L;

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

    public static EventData newPublishing(String eventId, String name, String venue, Instant eventDate, int capacity,
                                          BigDecimal price, String currency, Instant now) {
        return new EventData(eventId, name, venue, eventDate, capacity, price, currency, EventStatusEnum.PUBLISHING,
                capacity, 0, 0, 0, 0, INITIAL_VERSION, now, now);
    }

    public AvailabilitySnapshot toAvailabilitySnapshot(Instant capturedAt) {
        return new AvailabilitySnapshot(eventId, totalCapacity, availableCount, reservedCount, pendingCount,
                soldCount, complimentaryCount, capturedAt);
    }

    public boolean isOnSale() {
        return status == EventStatusEnum.ON_SALE;
    }

    public EventData withStatus(EventStatusEnum newStatus, Instant now) {
        return new EventData(eventId, name, venue, eventDate, totalCapacity, ticketPrice, currency, newStatus,
                availableCount, reservedCount, pendingCount, soldCount, complimentaryCount, version + 1,
                createdAt, now);
    }

    public EventData withCounters(int newAvailableCount, int newReservedCount, int newPendingCount, int newSoldCount,
                                  int newComplimentaryCount, Instant now) {
        return new EventData(eventId, name, venue, eventDate, totalCapacity, ticketPrice, currency, status,
                newAvailableCount, newReservedCount, newPendingCount, newSoldCount, newComplimentaryCount,
                version + 1, createdAt, now);
    }
}
