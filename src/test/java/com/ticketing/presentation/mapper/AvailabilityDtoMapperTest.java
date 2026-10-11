package com.ticketing.presentation.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.domain.data.AvailabilitySnapshot;
import com.ticketing.presentation.dto.AvailabilityResponseDto;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AvailabilityDtoMapperTest {

    private final AvailabilityDtoMapper mapper = new AvailabilityDtoMapper();

    @Test
    void shouldMapEveryCounter() {
        Instant capturedAt = Instant.parse("2026-10-10T10:00:00Z");

        assertThat(mapper.toResponse(new AvailabilitySnapshot("e-1", 100, 60, 10, 5, 20, 5, capturedAt)))
                .isEqualTo(new AvailabilityResponseDto("e-1", 100, 60, 10, 5, 20, 5, capturedAt));
    }

    @Test
    void shouldRejectNull() {
        assertThatNullPointerException().isThrownBy(() -> mapper.toResponse(null));
    }
}
