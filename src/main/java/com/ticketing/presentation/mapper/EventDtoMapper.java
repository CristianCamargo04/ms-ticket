package com.ticketing.presentation.mapper;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.PageData;
import com.ticketing.domain.internal_request.CreateEventRequest;
import com.ticketing.domain.internal_request.ListEventsRequest;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.presentation.dto.CreateEventRequestDto;
import com.ticketing.presentation.dto.EventResponseDto;
import com.ticketing.presentation.dto.PageResponseDto;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class EventDtoMapper {
    public CreateEventRequest toCreateEventRequest(CreateEventRequestDto dto) {
        Objects.requireNonNull(dto, "dto");
        return new CreateEventRequest(dto.name(), dto.venue(), dto.eventDate(), dto.totalCapacity(),
                dto.ticketPrice(), dto.currency());
    }

    public ListEventsRequest toListEventsRequest(EventStatusEnum status, int limit, String cursor) {
        return new ListEventsRequest(status, limit, cursor == null || cursor.isBlank() ? null : cursor);
    }

    public EventResponseDto toResponse(EventData event) {
        Objects.requireNonNull(event, "event");
        return new EventResponseDto(event.eventId(), event.name(), event.venue(), event.eventDate(),
                event.totalCapacity(), event.ticketPrice(), event.currency(), event.status(),
                event.availableCount(), event.createdAt());
    }

    public PageResponseDto<EventResponseDto> toPageResponse(PageData<EventData> page) {
        Objects.requireNonNull(page, "page");
        return new PageResponseDto<>(page.items().stream().map(this::toResponse).toList(), page.nextCursor());
    }
}
