package com.ticketing.presentation.impl.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.presentation.dto.message.OrderReservationMessageRequestDto;
import com.ticketing.presentation.dto.message.PaymentRequestedMessageRequestDto;
import com.ticketing.presentation.impl.support.JsonMessageReader.MalformedMessageException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class JsonMessageReaderTest {

    private static final String RESERVATION = """
            {"orderId":"o-1","eventId":"e-1","correlationId":"corr-1","occurredAt":"2026-10-10T10:00:00Z"}
            """;

    private static ValidatorFactory validatorFactory;

    private JsonMessageReader reader;

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
        reader = new JsonMessageReader(JsonMapper.builder().build(), validatorFactory.getValidator());
    }

    @Test
    void shouldReadAValidMessage() {
        StepVerifier.create(reader.read(RESERVATION, OrderReservationMessageRequestDto.class))
                .expectNext(new OrderReservationMessageRequestDto("o-1", "e-1", "corr-1",
                        Instant.parse("2026-10-10T10:00:00Z")))
                .verifyComplete();
    }

    @Test
    void shouldIgnoreUnknownPropertiesEvenWhenTheMapperIsStrict() {
        String payload = RESERVATION.replace("}", ",\"traceparent\":\"00-abc\"}");
        JsonMessageReader strictReader = new JsonMessageReader(
                JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build(),
                validatorFactory.getValidator());

        StepVerifier.create(strictReader.read(payload, OrderReservationMessageRequestDto.class))
                .assertNext(message -> assertThat(message.orderId()).isEqualTo("o-1"))
                .verifyComplete();
    }

    @Test
    void shouldReportSyntaxErrorsWithoutEchoingThePayload() {
        StepVerifier.create(reader.read("{\"orderId\":\"secret-value\"", OrderReservationMessageRequestDto.class))
                .verifyErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(MalformedMessageException.class)
                        .hasCauseInstanceOf(JacksonException.class)
                        .hasMessage("OrderReservationMessageRequestDto is not valid JSON for its schema"));
    }

    @Test
    void shouldReportTypeMismatches() {
        StepVerifier.create(reader.read("{\"occurredAt\":\"yesterday\"}", OrderReservationMessageRequestDto.class))
                .verifyError(MalformedMessageException.class);
    }

    @Test
    void shouldRejectNullDocuments() {
        StepVerifier.create(reader.read("null", OrderReservationMessageRequestDto.class))
                .verifyErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(MalformedMessageException.class)
                        .hasMessageContaining("is null"));
    }

    @Test
    void shouldRejectEmptyPayloads() {
        StepVerifier.create(reader.read(null, OrderReservationMessageRequestDto.class))
                .verifyErrorMessage("OrderReservationMessageRequestDto is empty");
        StepVerifier.create(reader.read("  ", OrderReservationMessageRequestDto.class))
                .verifyError(MalformedMessageException.class);
    }

    @Test
    void shouldListTheViolatedConstraintsWithoutTheRejectedValues() {
        String payload = """
                {"orderId":"","paymentToken":"tok secret","correlationId":"corr 1","occurredAt":"2026-10-10T10:00:00Z"}
                """;

        StepVerifier.create(reader.read(payload, PaymentRequestedMessageRequestDto.class))
                .verifyErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(MalformedMessageException.class)
                        .hasNoCause()
                        .hasMessageStartingWith("PaymentRequestedMessageRequestDto violates its schema: correlationId")
                        .hasMessageContaining("orderId")
                        .hasMessageContaining("paymentToken")
                        .hasMessageNotContaining("secret"));
    }

    @Test
    void shouldRequireDependenciesAndType() {
        assertThatNullPointerException().isThrownBy(() -> new JsonMessageReader(null,
                validatorFactory.getValidator()));
        assertThatNullPointerException().isThrownBy(() -> new JsonMessageReader(JsonMapper.builder().build(), null));
        assertThatNullPointerException().isThrownBy(() -> reader.read(RESERVATION, null));
    }
}
