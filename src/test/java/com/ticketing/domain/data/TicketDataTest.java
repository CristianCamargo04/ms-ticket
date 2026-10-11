package com.ticketing.domain.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.enums.TicketStatusEnum;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TicketDataTest {
    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

    @Test
    void shouldCreateAvailableTicket() {
        TicketData ticket = TicketData.available("e-1", "T-000001", NOW);

        assertThat(ticket).isEqualTo(new TicketData("e-1", "T-000001", TicketStatusEnum.AVAILABLE, null, null,
                TicketData.INITIAL_VERSION, NOW));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "1,      T-000001",
            "42,     T-000042",
            "20000,  T-020000",
            "999999, T-999999"
    })
    void shouldFormatTicketIdsWithZeroPadding(int sequence, String expected) {
        assertThat(TicketData.ticketIdFor(sequence)).isEqualTo(expected);
    }

    @Test
    void shouldKeepLexicographicOrderEqualToNumericOrder() {
        assertThat(TicketData.ticketIdFor(9)).isLessThan(TicketData.ticketIdFor(10));
        assertThat(TicketData.ticketIdFor(999)).isLessThan(TicketData.ticketIdFor(1000));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1_000_000})
    void shouldRejectSequenceOutOfRange(int sequence) {
        assertThatIllegalArgumentException().isThrownBy(() -> TicketData.ticketIdFor(sequence));
    }

    @Test
    void shouldApplyNewStateAndBumpVersion() {
        Instant later = NOW.plusSeconds(5);
        Instant holdExpiresAt = NOW.plusSeconds(600);

        TicketData reserved = TicketData.available("e-1", "T-000001", NOW)
                .withState(TicketStatusEnum.RESERVED, "o-1", holdExpiresAt, later);

        assertThat(reserved.status()).isEqualTo(TicketStatusEnum.RESERVED);
        assertThat(reserved.orderId()).isEqualTo("o-1");
        assertThat(reserved.holdExpiresAt()).isEqualTo(holdExpiresAt);
        assertThat(reserved.version()).isEqualTo(TicketData.INITIAL_VERSION + 1);
        assertThat(reserved.updatedAt()).isEqualTo(later);
        assertThat(reserved.eventId()).isEqualTo("e-1");
        assertThat(reserved.ticketId()).isEqualTo("T-000001");
    }

    @Test
    void shouldRejectInvalidComponents() {
        assertThatIllegalArgumentException().isThrownBy(() -> TicketData.available("", "T-000001", NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> TicketData.available("e-1", " ", NOW));
        assertThatNullPointerException().isThrownBy(() -> TicketData.available("e-1", "T-000001", null));
        assertThatNullPointerException()
                .isThrownBy(() -> new TicketData("e-1", "T-000001", null, null, null, 0, NOW));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new TicketData("e-1", "T-000001", TicketStatusEnum.AVAILABLE, null, null, -1, NOW));
    }
}
