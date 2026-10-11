package com.ticketing.domain.data;

import com.ticketing.domain.validation.DomainPreconditions;
import java.time.Instant;
import java.util.Objects;

/**
 * Point-in-time view of the inventory counters of an event.
 *
 * @param eventId             event identifier
 * @param totalCapacity       total number of tickets of the event
 * @param available           tickets that can still be reserved
 * @param reserved            tickets held by a reservation awaiting confirmation
 * @param pendingConfirmation tickets awaiting the payment result
 * @param sold                tickets sold (accountable)
 * @param complimentary       complimentary tickets issued (final, not accountable)
 * @param capturedAt          when the counters were read
 */
public record AvailabilitySnapshot(String eventId, int totalCapacity, int available, int reserved,
                                   int pendingConfirmation, int sold, int complimentary, Instant capturedAt) {

    /**
     * Validates the snapshot: non-blank event id, non-negative counters, non-null capture instant.
     */
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

    /**
     * Compares every component except {@link #capturedAt()}; used to emit stream updates only when counters change.
     *
     * @param other the snapshot to compare with, may be {@code null}
     * @return {@code true} when both snapshots describe the same event with identical counters
     */
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
