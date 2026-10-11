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

/**
 * Maps the order DTOs and the caller context to the use-case requests, and orders to their representations. The
 * customer (or issuer) always comes from the authenticated {@link RequestContext}, never from a body field.
 */
@Component
public class OrderDtoMapper {

    /** Path template of the order status resource. */
    public static final String ORDER_STATUS_PATH = "/api/v1/orders/{orderId}";

    /**
     * Builds the place-order request.
     *
     * @param eventId        the event of the order
     * @param dto            the validated body
     * @param context        the caller (the customer is its subject)
     * @param idempotencyKey the validated idempotency key
     * @param requestHash    fingerprint of the request payload
     * @return the use-case request
     */
    public PlaceOrderRequest toPlaceOrderRequest(String eventId, PlaceOrderRequestDto dto, RequestContext context,
                                                 String idempotencyKey, String requestHash) {
        Objects.requireNonNull(dto, "dto");
        Objects.requireNonNull(context, "context");
        return new PlaceOrderRequest(eventId, context.subject(), dto.quantity(), idempotencyKey, requestHash,
                context.correlationId());
    }

    /**
     * Builds the complimentary-issue request.
     *
     * @param eventId        the event of the order
     * @param dto            the validated body
     * @param context        the caller (the issuer is its subject)
     * @param idempotencyKey the validated idempotency key
     * @param requestHash    fingerprint of the request payload
     * @return the use-case request
     */
    public IssueComplimentaryRequest toIssueComplimentaryRequest(String eventId, IssueComplimentaryRequestDto dto,
                                                                 RequestContext context, String idempotencyKey,
                                                                 String requestHash) {
        Objects.requireNonNull(dto, "dto");
        Objects.requireNonNull(context, "context");
        return new IssueComplimentaryRequest(eventId, dto.beneficiaryId(), dto.quantity(), context.subject(),
                idempotencyKey, requestHash, context.correlationId());
    }

    /**
     * Builds the get-order request; administrators are privileged (they may read any order).
     *
     * @param orderId the order identifier
     * @param context the caller
     * @return the use-case request
     */
    public GetOrderRequest toGetOrderRequest(String orderId, RequestContext context) {
        Objects.requireNonNull(context, "context");
        return new GetOrderRequest(orderId, context.subject(), context.isAdmin());
    }

    /**
     * Builds the confirm-order request.
     *
     * @param orderId the order identifier
     * @param dto     the validated body
     * @param context the caller (must own the order)
     * @return the use-case request
     */
    public ConfirmOrderRequest toConfirmOrderRequest(String orderId, ConfirmOrderRequestDto dto,
                                                     RequestContext context) {
        Objects.requireNonNull(dto, "dto");
        Objects.requireNonNull(context, "context");
        return new ConfirmOrderRequest(orderId, context.subject(), dto.paymentToken(), context.correlationId());
    }

    /**
     * Builds the cancel-order request.
     *
     * @param orderId the order identifier
     * @param context the caller (must own the order)
     * @return the use-case request
     */
    public CancelOrderRequest toCancelOrderRequest(String orderId, RequestContext context) {
        Objects.requireNonNull(context, "context");
        return new CancelOrderRequest(orderId, context.subject());
    }

    /**
     * Maps an accepted (queued) order.
     *
     * @param order the order
     * @return the response body, with the URL to poll its status
     */
    public OrderAcceptedResponseDto toAcceptedResponse(OrderData order) {
        Objects.requireNonNull(order, "order");
        return new OrderAcceptedResponseDto(order.orderId(), order.ticketStatus(), order.processingStatus(),
                statusUri(order.orderId()).toString());
    }

    /**
     * Maps an order to its status representation.
     *
     * @param order the order
     * @return the response body
     */
    public OrderStatusResponseDto toStatusResponse(OrderData order) {
        Objects.requireNonNull(order, "order");
        return new OrderStatusResponseDto(order.orderId(), order.eventId(), order.type(), order.quantity(),
                order.ticketStatus(), order.processingStatus(), order.reason(), order.ticketIds(),
                order.holdExpiresAt(), order.createdAt(), order.updatedAt());
    }

    /**
     * Relative URI of the order status resource (used for {@code Location} headers and {@code statusUrl}).
     *
     * @param orderId the order identifier
     * @return {@code /api/v1/orders/{orderId}} with the id URI-encoded
     */
    public URI statusUri(String orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return UriComponentsBuilder.fromPath(ORDER_STATUS_PATH).encode().buildAndExpand(orderId).toUri();
    }
}
