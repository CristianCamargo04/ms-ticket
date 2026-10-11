package com.ticketing.presentation.mapper;

import com.ticketing.domain.data.TicketTransitionData;
import com.ticketing.presentation.dto.TicketTransitionResponseDto;
import org.mapstruct.Mapper;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.factory.Mappers;

@Mapper(
        componentModel = "spring",
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface TicketTransitionDtoMapper {

    TicketTransitionDtoMapper INSTANCE = Mappers.getMapper(TicketTransitionDtoMapper.class);

    TicketTransitionResponseDto toResponse(TicketTransitionData transition);
}
