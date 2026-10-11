package com.ticketing.client.mapper;

import com.ticketing.client.dto.sqs.OrderReservationMessageDto;
import com.ticketing.client.dto.sqs.PaymentRequestedMessageDto;
import com.ticketing.domain.internal_request.OrderReservationMessage;
import com.ticketing.domain.internal_request.PaymentRequestedMessage;
import com.ticketing.enums.MessageTypeEnum;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * Translates the domain order messages into their SQS wire representation: the JSON body DTOs of
 * {@code client.dto.sqs} and the message attributes ({@code messageType}, {@code correlationId},
 * {@code schemaVersion}).
 */
@Component
public class SqsOrderQueueClientMapper {

    /** Attribute holding the {@link MessageTypeEnum} name. */
    public static final String MESSAGE_TYPE_ATTRIBUTE = "messageType";

    /** Attribute holding the correlation id of the original request. */
    public static final String CORRELATION_ID_ATTRIBUTE = "correlationId";

    /** Attribute holding the version of the body schema. */
    public static final String SCHEMA_VERSION_ATTRIBUTE = "schemaVersion";

    /** Current version of both message body schemas ({@code templates/messages/*.schema.json}). */
    public static final String SCHEMA_VERSION = "1";

    private static final String STRING_TYPE = "String";
    private static final String NUMBER_TYPE = "Number";

    /**
     * Body of an order-reservation message.
     *
     * @param message the domain message
     * @return the wire DTO
     */
    public OrderReservationMessageDto toDto(OrderReservationMessage message) {
        Objects.requireNonNull(message, "message");
        return new OrderReservationMessageDto(message.orderId(), message.eventId(), message.correlationId(),
                message.occurredAt());
    }

    /**
     * Body of an order-payment message.
     *
     * @param message the domain message
     * @return the wire DTO
     */
    public PaymentRequestedMessageDto toDto(PaymentRequestedMessage message) {
        Objects.requireNonNull(message, "message");
        return new PaymentRequestedMessageDto(message.orderId(), message.paymentToken(), message.correlationId(),
                message.occurredAt());
    }

    /**
     * Message attributes of a published message. SQS rejects empty attribute values, so a missing or blank
     * correlation id is simply omitted.
     *
     * @param messageType   the message type
     * @param correlationId the correlation id, may be {@code null}
     * @return the attributes (immutable)
     */
    public Map<String, MessageAttributeValue> toMessageAttributes(MessageTypeEnum messageType, String correlationId) {
        Objects.requireNonNull(messageType, "messageType");
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(MESSAGE_TYPE_ATTRIBUTE, attribute(STRING_TYPE, messageType.name()));
        if (correlationId != null && !correlationId.isBlank()) {
            attributes.put(CORRELATION_ID_ATTRIBUTE, attribute(STRING_TYPE, correlationId));
        }
        attributes.put(SCHEMA_VERSION_ATTRIBUTE, attribute(NUMBER_TYPE, SCHEMA_VERSION));
        return Map.copyOf(attributes);
    }

    private static MessageAttributeValue attribute(String dataType, String value) {
        return MessageAttributeValue.builder().dataType(dataType).stringValue(value).build();
    }
}
