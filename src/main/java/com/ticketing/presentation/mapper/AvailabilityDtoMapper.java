package com.ticketing.presentation.mapper;

import com.ticketing.domain.data.AvailabilitySnapshot;
import com.ticketing.presentation.dto.AvailabilityResponseDto;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class AvailabilityDtoMapper {
    public AvailabilityResponseDto toResponse(AvailabilitySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new AvailabilityResponseDto(snapshot.eventId(), snapshot.totalCapacity(), snapshot.available(),
                snapshot.reserved(), snapshot.pendingConfirmation(), snapshot.sold(), snapshot.complimentary(),
                snapshot.capturedAt());
    }
}
