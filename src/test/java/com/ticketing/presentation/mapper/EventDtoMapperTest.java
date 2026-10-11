package com.ticketing.presentation.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.PageData;
import com.ticketing.domain.internal_request.CreateEventRequest;
import com.ticketing.domain.internal_request.ListEventsRequest;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.presentation.dto.CreateEventRequestDto;
import com.ticketing.presentation.dto.EventResponseDto;
import com.ticketing.presentation.dto.PageResponseDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventDtoMapperTest {
    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
    private static final Instant EVENT_DATE = Instant.parse("2027-01-01T20:00:00Z");

    private final EventDtoMapper mapper = new EventDtoMapper();

    private static EventData event(String eventId) {
        return new EventData(eventId, "Concert", "Arena", EVENT_DATE, 100, new BigDecimal("49.90"), "USD",
                EventStatusEnum.ON_SALE, 90, 5, 3, 2, 0, 4, NOW, NOW.plusSeconds(60));
    }

    @Test
    void shouldMapTheCreationBody() {
        CreateEventRequestDto dto = new CreateEventRequestDto("Concert", "Arena", EVENT_DATE, 100,
                new BigDecimal("49.90"), "USD");

        assertThat(mapper.toCreateEventRequest(dto)).isEqualTo(new CreateEventRequest("Concert", "Arena", EVENT_DATE,
                100, new BigDecimal("49.90"), "USD"));
        assertThatNullPointerException().isThrownBy(() -> mapper.toCreateEventRequest(null));
    }

    @Test
    void shouldMapTheListingQueryAndTreatBlankCursorsAsFirstPage() {
        assertThat(mapper.toListEventsRequest(EventStatusEnum.ON_SALE, 20, "abc"))
                .isEqualTo(new ListEventsRequest(EventStatusEnum.ON_SALE, 20, "abc"));
        assertThat(mapper.toListEventsRequest(EventStatusEnum.CLOSED, 5, " ").cursor()).isNull();
        assertThat(mapper.toListEventsRequest(EventStatusEnum.CLOSED, 5, null).cursor()).isNull();
    }

    @Test
    void shouldMapAnEventExposingItsAvailableTickets() {
        assertThat(mapper.toResponse(event("e-1"))).isEqualTo(new EventResponseDto("e-1", "Concert", "Arena",
                EVENT_DATE, 100, new BigDecimal("49.90"), "USD", EventStatusEnum.ON_SALE, 90, NOW));
        assertThatNullPointerException().isThrownBy(() -> mapper.toResponse(null));
    }

    @Test
    void shouldMapAPageKeepingItsCursor() {
        PageResponseDto<EventResponseDto> page = mapper.toPageResponse(
                new PageData<>(List.of(event("e-1"), event("e-2")), "cursor-2"));

        assertThat(page.items()).extracting(EventResponseDto::eventId).containsExactly("e-1", "e-2");
        assertThat(page.nextCursor()).isEqualTo("cursor-2");
        assertThat(mapper.toPageResponse(PageData.empty())).isEqualTo(new PageResponseDto<>(List.of(), null));
        assertThatNullPointerException().isThrownBy(() -> mapper.toPageResponse(null));
    }
}
