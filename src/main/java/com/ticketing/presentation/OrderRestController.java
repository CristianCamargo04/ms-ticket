package com.ticketing.presentation;

import com.ticketing.presentation.dto.ConfirmOrderRequestDto;
import com.ticketing.presentation.dto.OrderAcceptedResponseDto;
import com.ticketing.presentation.dto.OrderStatusResponseDto;
import com.ticketing.presentation.dto.PlaceOrderRequestDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import reactor.core.publisher.Mono;

/**
 * REST contract of orders. The customer is always the JWT subject (resolved from the request), never a body field.
 */
@RequestMapping("/api/v1")
public interface OrderRestController {

    /** Header carrying the client idempotency key ({@code ^[A-Za-z0-9-]{8,64}$}). */
    String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /**
     * Accepts an order and queues its reservation (CUSTOMER, ADMIN).
     *
     * @param eventId        the event identifier
     * @param idempotencyKey the required idempotency key
     * @param request        the order body
     * @param httpRequest    the request (security and correlation context)
     * @return 202 with the accepted order and a {@code Location: /api/v1/orders/{orderId}} header
     */
    @PostMapping("/events/{eventId}/orders")
    Mono<ResponseEntity<OrderAcceptedResponseDto>> placeOrder(
            @PathVariable("eventId") String eventId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequestDto request,
            ServerHttpRequest httpRequest);

    /**
     * Reads an order (owner CUSTOMER or ADMIN).
     *
     * @param orderId     the order identifier
     * @param httpRequest the request (security and correlation context)
     * @return the order; 404 {@code ORDER_NOT_FOUND} when missing or owned by someone else
     */
    @GetMapping("/orders/{orderId}")
    Mono<OrderStatusResponseDto> getOrder(@PathVariable("orderId") String orderId, ServerHttpRequest httpRequest);

    /**
     * Confirms a reservation and requests its payment (owner CUSTOMER).
     *
     * @param orderId        the order identifier
     * @param idempotencyKey the required idempotency key
     * @param request        the confirmation body
     * @param httpRequest    the request (security and correlation context)
     * @return 202 with the order pending confirmation
     */
    @PostMapping("/orders/{orderId}/confirmation")
    Mono<ResponseEntity<OrderStatusResponseDto>> confirmOrder(
            @PathVariable("orderId") String orderId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody ConfirmOrderRequestDto request,
            ServerHttpRequest httpRequest);

    /**
     * Cancels a queued or reserved order (owner CUSTOMER).
     *
     * @param orderId     the order identifier
     * @param httpRequest the request (security and correlation context)
     * @return 202 with the cancelled order
     */
    @DeleteMapping("/orders/{orderId}/reservation")
    Mono<ResponseEntity<OrderStatusResponseDto>> cancelOrder(@PathVariable("orderId") String orderId,
                                                             ServerHttpRequest httpRequest);
}
