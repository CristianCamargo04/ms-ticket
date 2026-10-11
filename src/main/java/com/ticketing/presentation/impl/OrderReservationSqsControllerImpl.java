package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.ReserveTicketsUseCase;
import com.ticketing.domain.exception.TicketingException;
import com.ticketing.presentation.OrderReservationSqsController;
import com.ticketing.presentation.context.RequestContext;
import com.ticketing.presentation.dto.message.OrderReservationMessageRequestDto;
import com.ticketing.presentation.impl.support.JsonMessageReader;
import com.ticketing.presentation.mapper.OrderMessageDtoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@RequiredArgsConstructor
@Slf4j
@Component
public class OrderReservationSqsControllerImpl implements OrderReservationSqsController {

    private final JsonMessageReader messageReader;
    private final OrderMessageDtoMapper mapper;
    private final ReserveTicketsUseCase reserveTicketsUseCase;

    @Override
    public Mono<Void> handle(String payload) {
        return messageReader.read(payload, OrderReservationMessageRequestDto.class)
                .flatMap(message -> Mono.defer(() -> reserveTicketsUseCase.execute(
                                mapper.toReserveTicketsRequest(message)))
                        .doOnNext(order -> log.debug("Order {} processed: {} / {}", order.orderId(),
                                order.ticketStatus(), order.processingStatus()))
                        .then()
                        .onErrorResume(TicketingException.class,
                                error -> error.retryable() ? Mono.error(error) : acknowledge(message, error))
                        .contextWrite(Context.of(RequestContext.CORRELATION_ID_KEY, message.correlationId())));
    }

    private static Mono<Void> acknowledge(OrderReservationMessageRequestDto message, TicketingException error) {
        log.warn("Acknowledging order-reservation message of order {} [correlationId={}] after non-retryable {}: {}",
                message.orderId(), message.correlationId(), error.errorCode(), error.getMessage());
        return Mono.empty();
    }
}
