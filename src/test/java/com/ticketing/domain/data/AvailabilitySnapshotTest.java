package com.ticketing.domain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AvailabilitySnapshotTest {

    private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");
    private static final AvailabilitySnapshot BASE = new AvailabilitySnapshot("e-1", 100, 50, 20, 10, 15, 5, T0);

    @Test
    void shouldConsiderSnapshotsWithSameCountsEqualRegardlessOfCaptureInstant() {
        AvailabilitySnapshot later = new AvailabilitySnapshot("e-1", 100, 50, 20, 10, 15, 5, T0.plusSeconds(5));

        assertThat(BASE.sameCountsAs(later)).isTrue();
        assertThat(BASE.sameCountsAs(BASE)).isTrue();
    }

    static Stream<Arguments> differentSnapshots() {
        return Stream.of(
                Arguments.of("eventId", new AvailabilitySnapshot("e-2", 100, 50, 20, 10, 15, 5, T0)),
                Arguments.of("totalCapacity", new AvailabilitySnapshot("e-1", 101, 50, 20, 10, 15, 5, T0)),
                Arguments.of("available", new AvailabilitySnapshot("e-1", 100, 49, 20, 10, 15, 5, T0)),
                Arguments.of("reserved", new AvailabilitySnapshot("e-1", 100, 50, 21, 10, 15, 5, T0)),
                Arguments.of("pendingConfirmation", new AvailabilitySnapshot("e-1", 100, 50, 20, 11, 15, 5, T0)),
                Arguments.of("sold", new AvailabilitySnapshot("e-1", 100, 50, 20, 10, 16, 5, T0)),
                Arguments.of("complimentary", new AvailabilitySnapshot("e-1", 100, 50, 20, 10, 15, 6, T0)));
    }

    @ParameterizedTest(name = "differs by {0}")
    @MethodSource("differentSnapshots")
    void shouldDetectAnyCounterChange(String component, AvailabilitySnapshot other) {
        assertThat(BASE.sameCountsAs(other)).as(component).isFalse();
    }

    @Test
    void shouldNotMatchNull() {
        assertThat(BASE.sameCountsAs(null)).isFalse();
    }

    @Test
    void shouldRejectInvalidComponents() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AvailabilitySnapshot(" ", 1, 1, 0, 0, 0, 0, T0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AvailabilitySnapshot("e-1", 1, -1, 0, 0, 0, 0, T0));
        assertThatNullPointerException()
                .isThrownBy(() -> new AvailabilitySnapshot("e-1", 1, 1, 0, 0, 0, 0, null));
    }
}
