package com.ticketing.client.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.client.dto.sqs.OrderReservationMessageDto;
import com.ticketing.client.dto.sqs.PaymentRequestedMessageDto;
import com.ticketing.domain.internal_request.OrderReservationMessage;
import com.ticketing.domain.internal_request.PaymentRequestedMessage;
import com.ticketing.enums.MessageTypeEnum;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SqsOrderQueueClientMapperTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-10-10T12:00:00.123Z");

    private final SqsOrderQueueClientMapper mapper = new SqsOrderQueueClientMapper();

    private static boolean isSchemaValidCorrelationId(String correlationId) {
        return correlationId != null
                && SqsOrderQueueClientMapper.CORRELATION_ID_PATTERN.matcher(correlationId).matches();
    }

    @Test
    void shouldMapReservationMessageToDto() {
        OrderReservationMessageDto dto = mapper.toDto(
                new OrderReservationMessage("order-1", "event-1", "corr-1", OCCURRED_AT));

        assertThat(dto).isEqualTo(new OrderReservationMessageDto("order-1", "event-1", "corr-1", OCCURRED_AT));
    }

    @Test
    void shouldMapPaymentMessageToDtoAndMaskTheTokenInToString() {
        PaymentRequestedMessageDto dto = mapper.toDto(
                new PaymentRequestedMessage("order-1", "tok_secret", "corr-1", OCCURRED_AT));

        assertThat(dto).isEqualTo(new PaymentRequestedMessageDto("order-1", "tok_secret", "corr-1", OCCURRED_AT));
        assertThat(dto.toString()).contains("order-1", "corr-1", "paymentToken=***").doesNotContain("tok_secret");
    }

    @Test
    void shouldSerializeInstantsAsIso8601Strings() {
        JsonMapper jsonMapper = JsonMapper.builder().build();

        JsonNode reservation = jsonMapper.readTree(jsonMapper.writeValueAsString(
                mapper.toDto(new OrderReservationMessage("order-1", "event-1", "corr-1", OCCURRED_AT))));
        JsonNode payment = jsonMapper.readTree(jsonMapper.writeValueAsString(
                mapper.toDto(new PaymentRequestedMessage("order-1", "tok_1", "corr-1", OCCURRED_AT))));

        assertThat(reservation.get("occurredAt").asString()).isEqualTo("2026-10-10T12:00:00.123Z");
        assertThat(payment.get("occurredAt").asString()).isEqualTo("2026-10-10T12:00:00.123Z");
        assertThat(payment.get("paymentToken").asString()).isEqualTo("tok_1");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "has space", "semi;colon", "under_score", "header\r\ninjection"})
    void shouldGenerateASchemaValidCorrelationIdWhenTheMessageHasNoValidOne(String invalidCorrelationId) {
        OrderReservationMessageDto reservation = mapper.toDto(
                new OrderReservationMessage("order-1", "event-1", invalidCorrelationId, OCCURRED_AT));
        PaymentRequestedMessageDto payment = mapper.toDto(
                new PaymentRequestedMessage("order-1", "tok_1", invalidCorrelationId, OCCURRED_AT));

        assertThat(reservation.correlationId()).matches(SqsOrderQueueClientMapper.CORRELATION_ID_PATTERN)
                .isNotEqualTo(invalidCorrelationId);
        assertThat(payment.correlationId()).matches(SqsOrderQueueClientMapper.CORRELATION_ID_PATTERN)
                .isNotEqualTo(invalidCorrelationId);
        assertThat(reservation.orderId()).isEqualTo("order-1");
        assertThat(payment.paymentToken()).isEqualTo("tok_1");
    }

    @Test
    void shouldNeverSerializeANullCorrelationId() {
        JsonMapper jsonMapper = JsonMapper.builder().build();

        JsonNode body = jsonMapper.readTree(jsonMapper.writeValueAsString(
                mapper.toDto(new OrderReservationMessage("order-1", "event-1", null, OCCURRED_AT))));

        assertThat(body.get("correlationId").isString()).isTrue();
        assertThat(isSchemaValidCorrelationId(body.get("correlationId").asString())).isTrue();
    }

    @Test
    void shouldKeepCorrelationIdsUpToTheSchemaMaximumLength() {
        String longest = "a".repeat(64);

        assertThat(SqsOrderQueueClientMapper.correlationIdOrNew(longest)).isEqualTo(longest);
        assertThat(SqsOrderQueueClientMapper.correlationIdOrNew("Corr-01")).isEqualTo("Corr-01");
        assertThat(SqsOrderQueueClientMapper.correlationIdOrNew("a".repeat(65)))
                .matches(SqsOrderQueueClientMapper.CORRELATION_ID_PATTERN)
                .isNotEqualTo("a".repeat(65));
    }

    @Test
    void shouldGenerateADifferentCorrelationIdEachTime() {
        assertThat(SqsOrderQueueClientMapper.correlationIdOrNew(null))
                .isNotEqualTo(SqsOrderQueueClientMapper.correlationIdOrNew(null));
    }

    @Test
    void shouldBuildMessageAttributesWithCorrelationId() {
        Map<String, MessageAttributeValue> attributes =
                mapper.toMessageAttributes(MessageTypeEnum.PAYMENT_REQUESTED, "corr-1");

        assertThat(attributes).containsOnlyKeys("messageType", "correlationId", "schemaVersion");
        assertThat(attributes.get(SqsOrderQueueClientMapper.MESSAGE_TYPE_ATTRIBUTE))
                .isEqualTo(MessageAttributeValue.builder().dataType("String").stringValue("PAYMENT_REQUESTED").build());
        assertThat(attributes.get(SqsOrderQueueClientMapper.CORRELATION_ID_ATTRIBUTE))
                .isEqualTo(MessageAttributeValue.builder().dataType("String").stringValue("corr-1").build());
        assertThat(attributes.get(SqsOrderQueueClientMapper.SCHEMA_VERSION_ATTRIBUTE))
                .isEqualTo(MessageAttributeValue.builder().dataType("Number").stringValue("1").build());
    }

    @Test
    void shouldRejectNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> mapper.toDto((OrderReservationMessage) null));
        assertThatNullPointerException().isThrownBy(() -> mapper.toDto((PaymentRequestedMessage) null));
        assertThatNullPointerException().isThrownBy(() -> mapper.toMessageAttributes(null, "corr-1"));
        assertThatNullPointerException()
                .isThrownBy(() -> mapper.toMessageAttributes(MessageTypeEnum.PAYMENT_REQUESTED, null));
    }
}
