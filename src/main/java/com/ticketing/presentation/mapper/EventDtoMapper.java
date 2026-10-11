package com.ticketing.presentation.mapper;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.PageData;
import com.ticketing.domain.internal_request.CreateEventRequest;
import com.ticketing.domain.internal_request.ListEventsRequest;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.presentation.dto.CreateEventRequestDto;
import com.ticketing.presentation.dto.EventResponseDto;
import com.ticketing.presentation.dto.PageResponseDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.factory.Mappers;

@Mapper(
        componentModel = "spring",
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface EventDtoMapper {

    EventDtoMapper INSTANCE = Mappers.getMapper(EventDtoMapper.class);

    CreateEventRequest toCreateEventRequest(CreateEventRequestDto dto);

    @Mapping(target = "availableTickets", source = "availableCount")
    EventResponseDto toResponse(EventData event);

    default ListEventsRequest toListEventsRequest(EventStatusEnum status, int limit, String cursor) {
        return new ListEventsRequest(status, limit, cursor == null || cursor.isBlank() ? null : cursor);
    }

    default PageResponseDto<EventResponseDto> toPageResponse(PageData<EventData> page) {
        if (page == null) {
            return null;
        }
        return new PageResponseDto<>(page.items().stream().map(this::toResponse).toList(), page.nextCursor());
    }
}
