package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.ProcessOrderPaymentUseCase;
import com.ticketing.domain.exception.TicketingException;
import com.ticketing.presentation.OrderPaymentSqsController;
import com.ticketing.presentation.context.RequestContext;
import com.ticketing.presentation.dto.message.PaymentRequestedMessageRequestDto;
import com.ticketing.presentation.impl.support.JsonMessageReader;
import com.ticketing.presentation.mapper.OrderMessageDtoMapper;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Consumes the order-payment queue: charges a confirmed order and finalizes or releases its tickets.
 *
 * <p>Acknowledgement policy: completion deletes the message. A non-retryable business error is logged and
 * acknowledged; malformed messages and retryable failures (payment gateway unavailable, inventory conflict) propagate
 * as error signals so SQS redelivers the message, then dead-letters it. The payment token is never logged.
 */
@Component
public class OrderPaymentSqsControllerImpl implements OrderPaymentSqsController {

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentSqsControllerImpl.class);

    private final JsonMessageReader messageReader;
    private final OrderMessageDtoMapper mapper;
    private final ProcessOrderPaymentUseCase processOrderPaymentUseCase;

    /**
     * Creates the controller.
     *
     * @param messageReader              parses and validates message bodies
     * @param mapper                     maps messages to use-case requests
     * @param processOrderPaymentUseCase the payment use case
     */
    public OrderPaymentSqsControllerImpl(JsonMessageReader messageReader, OrderMessageDtoMapper mapper,
                                         ProcessOrderPaymentUseCase processOrderPaymentUseCase) {
        this.messageReader = Objects.requireNonNull(messageReader, "messageReader");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.processOrderPaymentUseCase = Objects.requireNonNull(processOrderPaymentUseCase,
                "processOrderPaymentUseCase");
    }

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
