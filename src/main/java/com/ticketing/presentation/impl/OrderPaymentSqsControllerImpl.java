package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.ProcessOrderPaymentUseCase;
import com.ticketing.domain.exception.TicketingException;
import com.ticketing.presentation.OrderPaymentSqsController;
import com.ticketing.presentation.context.RequestContext;
import com.ticketing.presentation.dto.message.PaymentRequestedMessageRequestDto;
import com.ticketing.presentation.impl.support.JsonMessageReader;
import com.ticketing.presentation.mapper.OrderMessageDtoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaymentSqsControllerImpl implements OrderPaymentSqsController {

    private final JsonMessageReader messageReader;
    private final OrderMessageDtoMapper mapper;
    private final ProcessOrderPaymentUseCase processOrderPaymentUseCase;

    @Override
    public Mono<Void> handle(String payload) {
        return messageReader.read(payload, PaymentRequestedMessageRequestDto.class)
                .flatMap(message -> Mono.defer(() -> processOrderPaymentUseCase.execute(
                                mapper.toProcessOrderPaymentRequest(message)))
                        .doOnNext(order -> log.debug("Payment of order {} processed: {} / {}", order.orderId(),
                                order.ticketStatus(), order.processingStatus()))
                        .then()
                        .onErrorResume(TicketingException.class,
                                error -> error.retryable() ? Mono.error(error) : acknowledge(message, error))
                        .contextWrite(Context.of(RequestContext.CORRELATION_ID_KEY, message.correlationId())));
    }

    private static Mono<Void> acknowledge(PaymentRequestedMessageRequestDto message, TicketingException error) {
        log.warn("Acknowledging order-payment message of order {} [correlationId={}] after non-retryable {}: {}",
                message.orderId(), message.correlationId(), error.errorCode(), error.getMessage());
        return Mono.empty();
    }
}
