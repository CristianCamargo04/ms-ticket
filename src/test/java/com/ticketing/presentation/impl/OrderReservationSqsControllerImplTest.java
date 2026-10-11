package com.ticketing.presentation.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketing.business.usecase.ReserveTicketsUseCase;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.exception.OrderNotFoundException;
import com.ticketing.domain.internal_request.ReserveTicketsRequest;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.presentation.context.RequestContext;
import com.ticketing.presentation.impl.support.JsonMessageReader;
import com.ticketing.presentation.impl.support.JsonMessageReader.MalformedMessageException;
import com.ticketing.presentation.mapper.OrderMessageDtoMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class OrderReservationSqsControllerImplTest {

    private static final String ORDER_ID = "0b1f6d2c-8a43-5e7f-9c21-4d5e6f708192";
    private static final String PAYLOAD = """
            {"orderId":"%s","eventId":"e-1","correlationId":"corr-42","occurredAt":"2026-10-10T10:00:00Z"}
            """.formatted(ORDER_ID);
    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

    private static ValidatorFactory validatorFactory;

    @Mock
    private ReserveTicketsUseCase reserveTicketsUseCase;

    private OrderReservationSqsControllerImpl controller;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @BeforeEach
    void setUp() {
        controller = new OrderReservationSqsControllerImpl(
                new JsonMessageReader(JsonMapper.builder().build(), validatorFactory.getValidator()),
                new OrderMessageDtoMapper(), reserveTicketsUseCase);
    }

    private static OrderData reservedOrder() {
        return OrderData.newQueued(ORDER_ID, "e-1", "customer-1", OrderTypeEnum.STANDARD, 1, "hash",
                NOW.plusSeconds(30), NOW).withReservation(List.of("T-000001"), NOW.plusSeconds(600), NOW);
    }

    @Test
    void shouldReserveTheTicketsOfTheMessage() {
        given(reserveTicketsUseCase.execute(new ReserveTicketsRequest(ORDER_ID, "corr-42")))
                .willReturn(Mono.just(reservedOrder()));

        StepVerifier.create(controller.handle(PAYLOAD)).verifyComplete();
    }

    @Test
    void shouldPropagateTheCorrelationIdInTheReactorContext() {
        AtomicReference<String> correlationId = new AtomicReference<>();
        given(reserveTicketsUseCase.execute(any())).willReturn(Mono.deferContextual(context -> {
            correlationId.set(context.get(RequestContext.CORRELATION_ID_KEY));
            return Mono.just(reservedOrder());
        }));

        StepVerifier.create(controller.handle(PAYLOAD)).verifyComplete();
        assertThat(correlationId).hasValue("corr-42");
    }

    @Test
    void shouldAcknowledgeDuplicateOrUnchangedDeliveries() {
        given(reserveTicketsUseCase.execute(any())).willReturn(Mono.empty());

        StepVerifier.create(controller.handle(PAYLOAD)).verifyComplete();
    }

    @Test
    void shouldAcknowledgeNonRetryableBusinessErrors() {
        given(reserveTicketsUseCase.execute(any())).willReturn(Mono.error(new OrderNotFoundException(ORDER_ID)));

        StepVerifier.create(controller.handle(PAYLOAD)).verifyComplete();
    }

    @Test
    void shouldAcknowledgeNonRetryableErrorsThrownSynchronously() {
        given(reserveTicketsUseCase.execute(any())).willThrow(new OrderNotFoundException(ORDER_ID));

        StepVerifier.create(controller.handle(PAYLOAD)).verifyComplete();
    }

    @Test
    void shouldPropagateRetryableErrorsSoTheMessageIsRedelivered() {
        InventoryConflictException conflict = new InventoryConflictException("retries exhausted");
        given(reserveTicketsUseCase.execute(any())).willReturn(Mono.error(conflict));

        StepVerifier.create(controller.handle(PAYLOAD)).verifyErrorSatisfies(error -> assertThat(error)
                .isSameAs(conflict));
    }

    @Test
    void shouldPropagateUnexpectedErrors() {
        given(reserveTicketsUseCase.execute(any())).willReturn(Mono.error(new IllegalStateException("bug")));

        StepVerifier.create(controller.handle(PAYLOAD)).verifyError(IllegalStateException.class);
    }

    @Test
    void shouldRejectMalformedJsonWithoutCallingTheUseCase() {
        StepVerifier.create(controller.handle("{not json")).verifyError(MalformedMessageException.class);
        verifyNoInteractions(reserveTicketsUseCase);
    }

    @Test
    void shouldRejectMessagesMissingRequiredFields() {
        StepVerifier.create(controller.handle("{\"orderId\":\"o-1\",\"eventId\":\"e-1\",\"correlationId\":\"c-1\"}"))
                .verifyErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(MalformedMessageException.class)
                        .hasMessageContaining("occurredAt"));
        verifyNoInteractions(reserveTicketsUseCase);
    }

    @Test
    void shouldRejectEmptyPayloads() {
        StepVerifier.create(controller.handle(null)).verifyError(MalformedMessageException.class);
        StepVerifier.create(controller.handle(" ")).verifyError(MalformedMessageException.class);
        verifyNoInteractions(reserveTicketsUseCase);
    }

    @Test
    void shouldIgnoreUnknownFields() {
        given(reserveTicketsUseCase.execute(any())).willReturn(Mono.just(reservedOrder()));

        StepVerifier.create(controller.handle(PAYLOAD.replace("}", ",\"schemaVersion\":2}"))).verifyComplete();
        verify(reserveTicketsUseCase).execute(new ReserveTicketsRequest(ORDER_ID, "corr-42"));
    }
}
