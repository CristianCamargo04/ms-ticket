package com.ticketing.client.mapper;

import com.ticketing.client.dto.sqs.OrderReservationMessageDto;
import com.ticketing.client.dto.sqs.PaymentRequestedMessageDto;
import com.ticketing.domain.internal_request.OrderReservationMessage;
import com.ticketing.domain.internal_request.PaymentRequestedMessage;
import com.ticketing.enums.MessageTypeEnum;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

@Component
public class SqsOrderQueueClientMapper {
    public static final String MESSAGE_TYPE_ATTRIBUTE = "messageType";

    public static final String CORRELATION_ID_ATTRIBUTE = "correlationId";

    public static final String SCHEMA_VERSION_ATTRIBUTE = "schemaVersion";

    public static final String SCHEMA_VERSION = "1";

    public static final Pattern CORRELATION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private static final String STRING_TYPE = "String";
    private static final String NUMBER_TYPE = "Number";

    public OrderReservationMessageDto toDto(OrderReservationMessage message) {
        Objects.requireNonNull(message, "message");
        return new OrderReservationMessageDto(message.orderId(), message.eventId(),
                correlationIdOrNew(message.correlationId()), message.occurredAt());
    }

    public PaymentRequestedMessageDto toDto(PaymentRequestedMessage message) {
        Objects.requireNonNull(message, "message");
        return new PaymentRequestedMessageDto(message.orderId(), message.paymentToken(),
                correlationIdOrNew(message.correlationId()), message.occurredAt());
    }

    public Map<String, MessageAttributeValue> toMessageAttributes(MessageTypeEnum messageType, String correlationId) {
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(correlationId, "correlationId");
        return Map.of(
                MESSAGE_TYPE_ATTRIBUTE, attribute(STRING_TYPE, messageType.name()),
                CORRELATION_ID_ATTRIBUTE, attribute(STRING_TYPE, correlationId),
                SCHEMA_VERSION_ATTRIBUTE, attribute(NUMBER_TYPE, SCHEMA_VERSION));
    }

    static String correlationIdOrNew(String correlationId) {
        if (correlationId != null && CORRELATION_ID_PATTERN.matcher(correlationId).matches()) {
            return correlationId;
        }
        return UUID.randomUUID().toString();
    }

    private static MessageAttributeValue attribute(String dataType, String value) {
        return MessageAttributeValue.builder().dataType(dataType).stringValue(value).build();
    }
}
