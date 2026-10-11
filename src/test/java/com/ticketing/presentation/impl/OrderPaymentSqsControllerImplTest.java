package com.ticketing.presentation.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketing.business.usecase.ProcessOrderPaymentUseCase;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.exception.InvalidOrderStateException;
import com.ticketing.domain.exception.OrderNotFoundException;
import com.ticketing.domain.exception.PaymentGatewayException;
import com.ticketing.domain.internal_request.ProcessOrderPaymentRequest;
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
class OrderPaymentSqsControllerImplTest {

    private static final String ORDER_ID = "0b1f6d2c-8a43-5e7f-9c21-4d5e6f708192";
    private static final String PAYLOAD = """
            {"orderId":"%s","paymentToken":"tok_visa-1","correlationId":"corr-7","occurredAt":"2026-10-10T10:05:00Z"}
            """.formatted(ORDER_ID);
    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

    private static ValidatorFactory validatorFactory;

    @Mock
    private ProcessOrderPaymentUseCase processOrderPaymentUseCase;

    private OrderPaymentSqsControllerImpl controller;

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
        controller = new OrderPaymentSqsControllerImpl(
                new JsonMessageReader(JsonMapper.builder().build(), validatorFactory.getValidator()),
                new OrderMessageDtoMapper(), processOrderPaymentUseCase);
    }

    private static OrderData soldOrder() {
        return OrderData.newQueued(ORDER_ID, "e-1", "customer-1", OrderTypeEnum.STANDARD, 1, "hash",
                        NOW.plusSeconds(30), NOW)
                .withReservation(List.of("T-000001"), NOW.plusSeconds(600), NOW)
                .withPendingConfirmation(NOW.plusSeconds(300), NOW)
                .withSale("pay-1", NOW);
    }

    @Test
    void shouldProcessThePaymentOfTheMessage() {
        AtomicReference<String> correlationId = new AtomicReference<>();
        given(processOrderPaymentUseCase.execute(new ProcessOrderPaymentRequest(ORDER_ID, "tok_visa-1", "corr-7")))
                .willReturn(Mono.deferContextual(context -> {
                    correlationId.set(context.get(RequestContext.CORRELATION_ID_KEY));
                    return Mono.just(soldOrder());
                }));

        StepVerifier.create(controller.handle(PAYLOAD)).verifyComplete();
        assertThat(correlationId).hasValue("corr-7");
    }

    @Test
    void shouldAcknowledgeNonRetryableBusinessErrors() {
        given(processOrderPaymentUseCase.execute(any()))
                .willReturn(Mono.error(new OrderNotFoundException(ORDER_ID)));

        StepVerifier.create(controller.handle(PAYLOAD)).verifyComplete();
    }

    @Test
    void shouldAcknowledgeNonRetryableErrorsThrownSynchronously() {
        given(processOrderPaymentUseCase.execute(any()))
                .willThrow(new InvalidOrderStateException(ORDER_ID, "not pending"));

        StepVerifier.create(controller.handle(PAYLOAD)).verifyComplete();
    }

    @Test
    void shouldPropagateGatewayOutagesSoTheMessageIsRedelivered() {
        given(processOrderPaymentUseCase.execute(any()))
                .willReturn(Mono.error(new PaymentGatewayException("circuit open")));

        StepVerifier.create(controller.handle(PAYLOAD)).verifyError(PaymentGatewayException.class);
    }

    @Test
    void shouldPropagateUnexpectedErrors() {
        given(processOrderPaymentUseCase.execute(any())).willReturn(Mono.error(new IllegalStateException("bug")));

        StepVerifier.create(controller.handle(PAYLOAD)).verifyError(IllegalStateException.class);
    }

    @Test
    void shouldRejectInvalidPaymentTokensWithoutLeakingThem() {
        String payload = PAYLOAD.replace("tok_visa-1", "4111 1111 1111 1111");

        StepVerifier.create(controller.handle(payload))
                .verifyErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(MalformedMessageException.class)
                        .hasMessageContaining("paymentToken")
                        .hasMessageNotContaining("4111"));
        verifyNoInteractions(processOrderPaymentUseCase);
    }

    @Test
    void shouldRejectMalformedJson() {
        StepVerifier.create(controller.handle("[1,2,3]")).verifyError(MalformedMessageException.class);
        verifyNoInteractions(processOrderPaymentUseCase);
    }
}
