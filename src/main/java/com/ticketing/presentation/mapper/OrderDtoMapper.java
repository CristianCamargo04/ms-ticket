package com.ticketing.presentation.mapper;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.CancelOrderRequest;
import com.ticketing.domain.internal_request.ConfirmOrderRequest;
import com.ticketing.domain.internal_request.GetOrderRequest;
import com.ticketing.domain.internal_request.IssueComplimentaryRequest;
import com.ticketing.domain.internal_request.PlaceOrderRequest;
import com.ticketing.presentation.context.RequestContext;
import com.ticketing.presentation.dto.ConfirmOrderRequestDto;
import com.ticketing.presentation.dto.IssueComplimentaryRequestDto;
import com.ticketing.presentation.dto.OrderAcceptedResponseDto;
import com.ticketing.presentation.dto.OrderStatusResponseDto;
import com.ticketing.presentation.dto.PlaceOrderRequestDto;
import java.net.URI;
import java.util.Objects;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.factory.Mappers;
import org.springframework.web.util.UriComponentsBuilder;

@Mapper(
        componentModel = "spring",
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface OrderDtoMapper {

    OrderDtoMapper INSTANCE = Mappers.getMapper(OrderDtoMapper.class);

    String ORDER_STATUS_PATH = "/api/v1/orders/{orderId}";

    @Mapping(target = "customerId", source = "context.subject")
    @Mapping(target = "quantity", source = "dto.quantity")
    @Mapping(target = "correlationId", source = "context.correlationId")
    PlaceOrderRequest toPlaceOrderRequest(String eventId, PlaceOrderRequestDto dto, RequestContext context,
                                          String idempotencyKey, String requestHash);

    @Mapping(target = "beneficiaryId", source = "dto.beneficiaryId")
    @Mapping(target = "quantity", source = "dto.quantity")
    @Mapping(target = "issuedBy", source = "context.subject")
    @Mapping(target = "correlationId", source = "context.correlationId")
    IssueComplimentaryRequest toIssueComplimentaryRequest(String eventId, IssueComplimentaryRequestDto dto,
                                                          RequestContext context, String idempotencyKey,
                                                          String requestHash);

    @Mapping(target = "requesterId", source = "context.subject")
    @Mapping(target = "privileged", expression = "java(context.isAdmin())")
    GetOrderRequest toGetOrderRequest(String orderId, RequestContext context);

    @Mapping(target = "customerId", source = "context.subject")
    @Mapping(target = "paymentToken", source = "dto.paymentToken")
    @Mapping(target = "correlationId", source = "context.correlationId")
    ConfirmOrderRequest toConfirmOrderRequest(String orderId, ConfirmOrderRequestDto dto, RequestContext context);

    @Mapping(target = "customerId", source = "context.subject")
    CancelOrderRequest toCancelOrderRequest(String orderId, RequestContext context);

    @Mapping(target = "status", source = "ticketStatus")
    @Mapping(target = "statusUrl", expression = "java(statusUri(order.orderId()).toString())")
    OrderAcceptedResponseDto toAcceptedResponse(OrderData order);

    @Mapping(target = "status", source = "ticketStatus")
    OrderStatusResponseDto toStatusResponse(OrderData order);

    @Named("statusUri")
    default URI statusUri(String orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return UriComponentsBuilder.fromPath(ORDER_STATUS_PATH).encode().buildAndExpand(orderId).toUri();
    }
}
