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
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class OrderDtoMapper {
    public static final String ORDER_STATUS_PATH = "/api/v1/orders/{orderId}";

    public PlaceOrderRequest toPlaceOrderRequest(String eventId, PlaceOrderRequestDto dto, RequestContext context,
                                                 String idempotencyKey, String requestHash) {
        Objects.requireNonNull(dto, "dto");
        Objects.requireNonNull(context, "context");
        return new PlaceOrderRequest(eventId, context.subject(), dto.quantity(), idempotencyKey, requestHash,
                context.correlationId());
    }

    public IssueComplimentaryRequest toIssueComplimentaryRequest(String eventId, IssueComplimentaryRequestDto dto,
                                                                 RequestContext context, String idempotencyKey,
                                                                 String requestHash) {
        Objects.requireNonNull(dto, "dto");
        Objects.requireNonNull(context, "context");
        return new IssueComplimentaryRequest(eventId, dto.beneficiaryId(), dto.quantity(), context.subject(),
                idempotencyKey, requestHash, context.correlationId());
    }

    public GetOrderRequest toGetOrderRequest(String orderId, RequestContext context) {
        Objects.requireNonNull(context, "context");
        return new GetOrderRequest(orderId, context.subject(), context.isAdmin());
    }

    public ConfirmOrderRequest toConfirmOrderRequest(String orderId, ConfirmOrderRequestDto dto,
                                                     RequestContext context) {
        Objects.requireNonNull(dto, "dto");
        Objects.requireNonNull(context, "context");
        return new ConfirmOrderRequest(orderId, context.subject(), dto.paymentToken(), context.correlationId());
    }

    public CancelOrderRequest toCancelOrderRequest(String orderId, RequestContext context) {
        Objects.requireNonNull(context, "context");
        return new CancelOrderRequest(orderId, context.subject());
    }

    public OrderAcceptedResponseDto toAcceptedResponse(OrderData order) {
        Objects.requireNonNull(order, "order");
        return new OrderAcceptedResponseDto(order.orderId(), order.ticketStatus(), order.processingStatus(),
                statusUri(order.orderId()).toString());
    }

    public OrderStatusResponseDto toStatusResponse(OrderData order) {
        Objects.requireNonNull(order, "order");
        return new OrderStatusResponseDto(order.orderId(), order.eventId(), order.type(), order.quantity(),
                order.ticketStatus(), order.processingStatus(), order.reason(), order.ticketIds(),
                order.holdExpiresAt(), order.createdAt(), order.updatedAt());
    }

    public URI statusUri(String orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return UriComponentsBuilder.fromPath(ORDER_STATUS_PATH).encode().buildAndExpand(orderId).toUri();
    }
}
