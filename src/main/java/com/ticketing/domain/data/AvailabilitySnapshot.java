package com.ticketing.domain.data;

import com.ticketing.domain.validation.DomainPreconditions;
import java.time.Instant;
import java.util.Objects;

public record AvailabilitySnapshot(String eventId, int totalCapacity, int available, int reserved,
                                   int pendingConfirmation, int sold, int complimentary, Instant capturedAt) {
    public AvailabilitySnapshot {
        DomainPreconditions.requireNonBlank(eventId, "eventId");
        DomainPreconditions.requireNonNegative(totalCapacity, "totalCapacity");
        DomainPreconditions.requireNonNegative(available, "available");
        DomainPreconditions.requireNonNegative(reserved, "reserved");
        DomainPreconditions.requireNonNegative(pendingConfirmation, "pendingConfirmation");
        DomainPreconditions.requireNonNegative(sold, "sold");
        DomainPreconditions.requireNonNegative(complimentary, "complimentary");
        Objects.requireNonNull(capturedAt, "capturedAt");
    }

    public boolean sameCountsAs(AvailabilitySnapshot other) {
        return other != null
                && eventId.equals(other.eventId)
                && totalCapacity == other.totalCapacity
                && available == other.available
                && reserved == other.reserved
                && pendingConfirmation == other.pendingConfirmation
                && sold == other.sold
                && complimentary == other.complimentary;
    }
}
