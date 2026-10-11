package com.ticketing.domain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.enums.EventStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EventDataTest {
    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
    private static final Instant EVENT_DATE = Instant.parse("2026-12-24T20:00:00Z");
    private static final BigDecimal PRICE = new BigDecimal("49.90");

    private static EventData publishing() {
        return EventData.newPublishing("e-1", "Concert", "Arena", EVENT_DATE, 100, PRICE, "USD", NOW);
    }

    @Test
    void shouldCreatePublishingEventWithWholeCapacityAvailable() {
        EventData event = publishing();

        assertThat(event.eventId()).isEqualTo("e-1");
        assertThat(event.name()).isEqualTo("Concert");
        assertThat(event.venue()).isEqualTo("Arena");
        assertThat(event.eventDate()).isEqualTo(EVENT_DATE);
        assertThat(event.totalCapacity()).isEqualTo(100);
        assertThat(event.ticketPrice()).isEqualByComparingTo(PRICE);
        assertThat(event.currency()).isEqualTo("USD");
        assertThat(event.status()).isEqualTo(EventStatusEnum.PUBLISHING);
        assertThat(event.availableCount()).isEqualTo(100);
        assertThat(event.reservedCount()).isZero();
        assertThat(event.pendingCount()).isZero();
        assertThat(event.soldCount()).isZero();
        assertThat(event.complimentaryCount()).isZero();
        assertThat(event.version()).isEqualTo(EventData.INITIAL_VERSION);
        assertThat(event.createdAt()).isEqualTo(NOW);
        assertThat(event.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldBuildAvailabilitySnapshotFromCounters() {
        EventData event = publishing().withCounters(40, 30, 20, 7, 3, NOW);
        Instant capturedAt = NOW.plusSeconds(1);

        AvailabilitySnapshot snapshot = event.toAvailabilitySnapshot(capturedAt);

        assertThat(snapshot).isEqualTo(new AvailabilitySnapshot("e-1", 100, 40, 30, 20, 7, 3, capturedAt));
    }

    @ParameterizedTest
    @EnumSource(EventStatusEnum.class)
    void shouldBeOnSaleOnlyWhenStatusIsOnSale(EventStatusEnum status) {
        EventData event = publishing().withStatus(status, NOW);

        assertThat(event.isOnSale()).isEqualTo(status == EventStatusEnum.ON_SALE);
    }

    @Test
    void shouldChangeStatusAndBumpVersion() {
        Instant later = NOW.plusSeconds(10);

        EventData onSale = publishing().withStatus(EventStatusEnum.ON_SALE, later);

        assertThat(onSale.status()).isEqualTo(EventStatusEnum.ON_SALE);
        assertThat(onSale.updatedAt()).isEqualTo(later);
        assertThat(onSale.createdAt()).isEqualTo(NOW);
        assertThat(onSale.version()).isEqualTo(EventData.INITIAL_VERSION + 1);
        assertThat(onSale.availableCount()).isEqualTo(100);
    }

    @Test
    void shouldChangeCountersAndBumpVersion() {
        Instant later = NOW.plusSeconds(10);

        EventData updated = publishing().withCounters(90, 6, 2, 1, 1, later);

        assertThat(updated.availableCount()).isEqualTo(90);
        assertThat(updated.reservedCount()).isEqualTo(6);
        assertThat(updated.pendingCount()).isEqualTo(2);
        assertThat(updated.soldCount()).isEqualTo(1);
        assertThat(updated.complimentaryCount()).isEqualTo(1);
        assertThat(updated.status()).isEqualTo(EventStatusEnum.PUBLISHING);
        assertThat(updated.version()).isEqualTo(EventData.INITIAL_VERSION + 1);
        assertThat(updated.updatedAt()).isEqualTo(later);
    }

    @Test
    void shouldRejectNegativeCountersAndPrice() {
        assertThatIllegalArgumentException().isThrownBy(() -> publishing().withCounters(-1, 0, 0, 0, 0, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> publishing().withCounters(0, 0, 0, 0, -1, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> EventData.newPublishing("e-1", "Concert", "Arena",
                EVENT_DATE, 10, new BigDecimal("-1"), "USD", NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> EventData.newPublishing("e-1", "Concert", "Arena",
                EVENT_DATE, -1, PRICE, "USD", NOW));
    }

    @Test
    void shouldRejectMissingComponents() {
        assertThatIllegalArgumentException().isThrownBy(() -> EventData.newPublishing(" ", "Concert", "Arena",
                EVENT_DATE, 10, PRICE, "USD", NOW));
        assertThatNullPointerException().isThrownBy(() -> EventData.newPublishing("e-1", null, "Arena",
                EVENT_DATE, 10, PRICE, "USD", NOW));
        assertThatNullPointerException().isThrownBy(() -> EventData.newPublishing("e-1", "Concert", "Arena",
                null, 10, PRICE, "USD", NOW));
        assertThatNullPointerException().isThrownBy(() -> EventData.newPublishing("e-1", "Concert", "Arena",
                EVENT_DATE, 10, PRICE, "USD", null));
        assertThatNullPointerException().isThrownBy(() -> publishing().withStatus(null, NOW));
    }
}
