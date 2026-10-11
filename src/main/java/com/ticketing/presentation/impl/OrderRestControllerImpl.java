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
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Order endpoints. The customer is always the JWT subject of the caller; ownership is enforced by the use cases,
 * which answer {@code 404 ORDER_NOT_FOUND} for orders of other customers (no existence leak). Path identifiers are
 * checked by {@link ResourceIdValidator} before anything else.
 */
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

    /**
     * Creates the controller.
     *
     * @param placeOrderUseCase      queues new orders
     * @param getOrderStatusUseCase  reads orders
     * @param confirmOrderUseCase    confirms reservations
     * @param cancelOrderUseCase     cancels orders
     * @param requestContextResolver resolves the caller
     * @param idempotencyKeyResolver validates the idempotency key header
     * @param fingerprintCalculator  fingerprints idempotent requests
     * @param mapper                 DTO mapper
     */
    public OrderRestControllerImpl(PlaceOrderUseCase placeOrderUseCase, GetOrderStatusUseCase getOrderStatusUseCase,
                                   ConfirmOrderUseCase confirmOrderUseCase, CancelOrderUseCase cancelOrderUseCase,
                                   RequestContextResolver requestContextResolver,
                                   IdempotencyKeyResolver idempotencyKeyResolver,
                                   RequestFingerprintCalculator fingerprintCalculator, OrderDtoMapper mapper) {
        this.placeOrderUseCase = Objects.requireNonNull(placeOrderUseCase, "placeOrderUseCase");
        this.getOrderStatusUseCase = Objects.requireNonNull(getOrderStatusUseCase, "getOrderStatusUseCase");
        this.confirmOrderUseCase = Objects.requireNonNull(confirmOrderUseCase, "confirmOrderUseCase");
        this.cancelOrderUseCase = Objects.requireNonNull(cancelOrderUseCase, "cancelOrderUseCase");
        this.requestContextResolver = Objects.requireNonNull(requestContextResolver, "requestContextResolver");
        this.idempotencyKeyResolver = Objects.requireNonNull(idempotencyKeyResolver, "idempotencyKeyResolver");
        this.fingerprintCalculator = Objects.requireNonNull(fingerprintCalculator, "fingerprintCalculator");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

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
        // The key is validated for API consistency; confirmation is idempotent by order state (the payment gateway
        // is called with the order id as its own idempotency key).
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
