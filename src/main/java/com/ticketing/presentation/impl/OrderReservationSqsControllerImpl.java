package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.ReserveTicketsUseCase;
import com.ticketing.domain.exception.TicketingException;
import com.ticketing.presentation.OrderReservationSqsController;
import com.ticketing.presentation.context.RequestContext;
import com.ticketing.presentation.dto.message.OrderReservationMessageRequestDto;
import com.ticketing.presentation.impl.support.JsonMessageReader;
import com.ticketing.presentation.mapper.OrderMessageDtoMapper;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Consumes the order-reservation queue: reserves (or issues as complimentary) the tickets of a queued order.
 *
 * <p>Acknowledgement policy: completion deletes the message. A non-retryable business error (e.g. the order does not
 * exist) is logged and acknowledged, because redelivering it cannot succeed. Malformed messages and retryable
 * failures (inventory conflicts once the in-process retries are exhausted) propagate as error signals, so SQS
 * redelivers the message and finally moves it to the dead-letter queue.
 */
@Component
public class OrderReservationSqsControllerImpl implements OrderReservationSqsController {

    private static final Logger log = LoggerFactory.getLogger(OrderReservationSqsControllerImpl.class);

    private final JsonMessageReader messageReader;
    private final OrderMessageDtoMapper mapper;
    private final ReserveTicketsUseCase reserveTicketsUseCase;

    /**
     * Creates the controller.
     *
     * @param messageReader         parses and validates message bodies
     * @param mapper                maps messages to use-case requests
     * @param reserveTicketsUseCase the reservation use case
     */
    public OrderReservationSqsControllerImpl(JsonMessageReader messageReader, OrderMessageDtoMapper mapper,
                                             ReserveTicketsUseCase reserveTicketsUseCase) {
        this.messageReader = Objects.requireNonNull(messageReader, "messageReader");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.reserveTicketsUseCase = Objects.requireNonNull(reserveTicketsUseCase, "reserveTicketsUseCase");
    }

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
