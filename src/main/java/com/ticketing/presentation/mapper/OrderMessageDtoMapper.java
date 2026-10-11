package com.ticketing.presentation.mapper;

import com.ticketing.domain.internal_request.ProcessOrderPaymentRequest;
import com.ticketing.domain.internal_request.ReserveTicketsRequest;
import com.ticketing.presentation.dto.message.OrderReservationMessageRequestDto;
import com.ticketing.presentation.dto.message.PaymentRequestedMessageRequestDto;
import org.mapstruct.Mapper;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.factory.Mappers;

@Mapper(
        componentModel = "spring",
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface OrderMessageDtoMapper {

    OrderMessageDtoMapper INSTANCE = Mappers.getMapper(OrderMessageDtoMapper.class);

    ReserveTicketsRequest toReserveTicketsRequest(OrderReservationMessageRequestDto message);

    ProcessOrderPaymentRequest toProcessOrderPaymentRequest(PaymentRequestedMessageRequestDto message);
}
