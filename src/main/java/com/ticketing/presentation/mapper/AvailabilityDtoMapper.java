package com.ticketing.presentation.mapper;

import com.ticketing.domain.data.AvailabilitySnapshot;
import com.ticketing.presentation.dto.AvailabilityResponseDto;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Maps availability snapshots to their public representation (also the payload of the SSE events).
 */
@Component
public class AvailabilityDtoMapper {

    /**
     * Maps a snapshot.
     *
     * @param snapshot the snapshot
     * @return the response body
     */
    public AvailabilityResponseDto toResponse(AvailabilitySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new AvailabilityResponseDto(snapshot.eventId(), snapshot.totalCapacity(), snapshot.available(),
                snapshot.reserved(), snapshot.pendingConfirmation(), snapshot.sold(), snapshot.complimentary(),
                snapshot.capturedAt());
    }
}
