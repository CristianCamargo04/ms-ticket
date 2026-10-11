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

/**
 * Maps the event DTOs to the use-case requests and the event data to the public representation.
 */
@Component
public class EventDtoMapper {

    /**
     * Maps the creation body.
     *
     * @param dto the validated body
     * @return the use-case request
     */
    public CreateEventRequest toCreateEventRequest(CreateEventRequestDto dto) {
        Objects.requireNonNull(dto, "dto");
        return new CreateEventRequest(dto.name(), dto.venue(), dto.eventDate(), dto.totalCapacity(),
                dto.ticketPrice(), dto.currency());
    }

    /**
     * Maps the listing query; a blank cursor means the first page.
     *
     * @param status status filter
     * @param limit  requested page size (clamped by the use case)
     * @param cursor opaque cursor, nullable
     * @return the use-case request
     */
    public ListEventsRequest toListEventsRequest(EventStatusEnum status, int limit, String cursor) {
        return new ListEventsRequest(status, limit, cursor == null || cursor.isBlank() ? null : cursor);
    }

    /**
     * Maps an event to its public representation.
     *
     * @param event the event
     * @return the response body
     */
    public EventResponseDto toResponse(EventData event) {
        Objects.requireNonNull(event, "event");
        return new EventResponseDto(event.eventId(), event.name(), event.venue(), event.eventDate(),
                event.totalCapacity(), event.ticketPrice(), event.currency(), event.status(),
                event.availableCount(), event.createdAt());
    }

    /**
     * Maps a page of events.
     *
     * @param page the page
     * @return the response body
     */
    public PageResponseDto<EventResponseDto> toPageResponse(PageData<EventData> page) {
        Objects.requireNonNull(page, "page");
        return new PageResponseDto<>(page.items().stream().map(this::toResponse).toList(), page.nextCursor());
    }
}
