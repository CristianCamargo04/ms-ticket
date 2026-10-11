package com.ticketing.presentation.mapper;

import com.ticketing.domain.data.AvailabilitySnapshot;
import com.ticketing.presentation.dto.AvailabilityResponseDto;
import org.mapstruct.Mapper;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.factory.Mappers;

@Mapper(
        componentModel = "spring",
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface AvailabilityDtoMapper {

    AvailabilityDtoMapper INSTANCE = Mappers.getMapper(AvailabilityDtoMapper.class);

    AvailabilityResponseDto toResponse(AvailabilitySnapshot snapshot);
}
