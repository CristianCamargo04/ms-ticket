package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.CancelOrderUseCase;
import com.ticketing.business.usecase.ConfirmOrderUseCase;
import com.ticketing.business.usecase.GetOrderStatusUseCase;
import com.ticketing.business.usecase.PlaceOrderUseCase;
import com.ticketing.domain.data.OrderData;
import com.ticketing.presentation.OrderRestController;
import com.ticketing.presentation.context.RequestContextResolver;
import com.ticketing.presentation.dto.ConfirmOrderRequestDto;
import com.ticketing.presentation.dto.OrderAcceptedResponseDto;
import com.ticketing.presentation.dto.OrderStatusResponseDto;
import com.ticketing.presentation.dto.PlaceOrderRequestDto;
import com.ticketing.presentation.impl.idempotency.IdempotencyKeyResolver;
import com.ticketing.presentation.impl.idempotency.RequestFingerprintCalculator;
import com.ticketing.presentation.impl.support.ResourceIdValidator;
import com.ticketing.presentation.mapper.OrderDtoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
@RestController
public class OrderRestControllerImpl implements OrderRestController {
    private final PlaceOrderUseCase placeOrderUseCase;
    private final GetOrderStatusUseCase getOrderStatusUseCase;
    private final ConfirmOrderUseCase confirmOrderUseCase;
    private final CancelOrderUseCase cancelOrderUseCase;
    private final RequestContextResolver requestContextResolver;
    private final IdempotencyKeyResolver idempotencyKeyResolver;
    private final RequestFingerprintCalculator fingerprintCalculator;
    private final OrderDtoMapper mapper;

    @Override
    public Mono<ResponseEntity<OrderAcceptedResponseDto>> placeOrder(String eventId, String idempotencyKey,
                                                                     PlaceOrderRequestDto request,
                                                                     ServerHttpRequest httpRequest) {
        return Mono.fromCallable(() -> {
                    ResourceIdValidator.requireValid(ResourceIdValidator.EVENT_ID, eventId);
                    return idempotencyKeyResolver.resolve(idempotencyKey);
                })
                .flatMap(key -> requestContextResolver.resolve(httpRequest)
                        .flatMap(context -> placeOrderUseCase.execute(mapper.toPlaceOrderRequest(eventId, request,
                                context, key, fingerprintCalculator.placeOrder(eventId, request.quantity())))))
                .map(this::accepted);
    }

    @Override
    public Mono<OrderStatusResponseDto> getOrder(String orderId, ServerHttpRequest httpRequest) {
        return validOrderId(orderId)
                .flatMap(id -> requestContextResolver.resolve(httpRequest)
                        .flatMap(context -> getOrderStatusUseCase.execute(mapper.toGetOrderRequest(id, context))))
                .map(mapper::toStatusResponse);
    }

    @Override
    public Mono<ResponseEntity<OrderStatusResponseDto>> confirmOrder(String orderId, String idempotencyKey,
                                                                     ConfirmOrderRequestDto request,
                                                                     ServerHttpRequest httpRequest) {
        return Mono.fromCallable(() -> {
                    ResourceIdValidator.requireValid(ResourceIdValidator.ORDER_ID, orderId);
                    return idempotencyKeyResolver.resolve(idempotencyKey);
                })
                .flatMap(_ -> requestContextResolver.resolve(httpRequest))
                .flatMap(context -> confirmOrderUseCase.execute(
                        mapper.toConfirmOrderRequest(orderId, request, context)))
                .map(order -> ResponseEntity.accepted().body(mapper.toStatusResponse(order)));
    }

    @Override
    public Mono<ResponseEntity<OrderStatusResponseDto>> cancelOrder(String orderId, ServerHttpRequest httpRequest) {
        return validOrderId(orderId)
                .flatMap(id -> requestContextResolver.resolve(httpRequest)
                        .flatMap(context -> cancelOrderUseCase.execute(mapper.toCancelOrderRequest(id, context))))
                .map(order -> ResponseEntity.accepted().body(mapper.toStatusResponse(order)));
    }

    private static Mono<String> validOrderId(String orderId) {
        return Mono.fromCallable(() -> ResourceIdValidator.requireValid(ResourceIdValidator.ORDER_ID, orderId));
    }

    private ResponseEntity<OrderAcceptedResponseDto> accepted(OrderData order) {
        return ResponseEntity.accepted()
                .location(mapper.statusUri(order.orderId()))
                .body(mapper.toAcceptedResponse(order));
    }
}
