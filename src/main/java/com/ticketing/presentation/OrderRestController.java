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

@RequestMapping("/api/v1")
public interface OrderRestController {
    String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    @PostMapping("/events/{eventId}/orders")
    Mono<ResponseEntity<OrderAcceptedResponseDto>> placeOrder(
            @PathVariable("eventId") String eventId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequestDto request,
            ServerHttpRequest httpRequest);

    @GetMapping("/orders/{orderId}")
    Mono<OrderStatusResponseDto> getOrder(@PathVariable("orderId") String orderId, ServerHttpRequest httpRequest);

    @PostMapping("/orders/{orderId}/confirmation")
    Mono<ResponseEntity<OrderStatusResponseDto>> confirmOrder(
            @PathVariable("orderId") String orderId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ConfirmOrderRequestDto request,
            ServerHttpRequest httpRequest);

    @DeleteMapping("/orders/{orderId}/reservation")
    Mono<ResponseEntity<OrderStatusResponseDto>> cancelOrder(@PathVariable("orderId") String orderId,
                                                             ServerHttpRequest httpRequest);
}
