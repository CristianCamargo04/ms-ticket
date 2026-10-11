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

/**
 * Translates the domain order messages into their SQS wire representation: the JSON body DTOs of
 * {@code client.dto.sqs} and the message attributes ({@code messageType}, {@code correlationId},
 * {@code schemaVersion}).
 *
 * <p>Both v1 schemas ({@code templates/messages/*.schema.json}) require a {@code correlationId} matching
 * {@link #CORRELATION_ID_PATTERN}, and the consumers validate it. Messages produced outside an HTTP request (the
 * dispatch sweeper, republications of stored orders) may have none, so the body DTO always carries a valid one: the
 * message's own when it conforms, otherwise a freshly generated UUID. The publisher copies the body's correlation id
 * into the attributes, so body and attributes always agree.
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

    /** Correlation ids accepted by the v1 message schemas (the same rule as the {@code X-Correlation-Id} header). */
    public static final Pattern CORRELATION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private static final String STRING_TYPE = "String";
    private static final String NUMBER_TYPE = "Number";

    /**
     * Body of an order-reservation message.
     *
     * @param message the domain message
     * @return the wire DTO, always with a schema-valid correlation id
     */
    public OrderReservationMessageDto toDto(OrderReservationMessage message) {
        Objects.requireNonNull(message, "message");
        return new OrderReservationMessageDto(message.orderId(), message.eventId(),
                correlationIdOrNew(message.correlationId()), message.occurredAt());
    }

    /**
     * Body of an order-payment message.
     *
     * @param message the domain message
     * @return the wire DTO, always with a schema-valid correlation id
     */
    public PaymentRequestedMessageDto toDto(PaymentRequestedMessage message) {
        Objects.requireNonNull(message, "message");
        return new PaymentRequestedMessageDto(message.orderId(), message.paymentToken(),
                correlationIdOrNew(message.correlationId()), message.occurredAt());
    }

    /**
     * Message attributes of a published message.
     *
     * @param messageType   the message type
     * @param correlationId the correlation id of the body DTO (so that body and attributes agree)
     * @return the attributes (immutable)
     */
    public Map<String, MessageAttributeValue> toMessageAttributes(MessageTypeEnum messageType, String correlationId) {
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(correlationId, "correlationId");
        return Map.of(
                MESSAGE_TYPE_ATTRIBUTE, attribute(STRING_TYPE, messageType.name()),
                CORRELATION_ID_ATTRIBUTE, attribute(STRING_TYPE, correlationId),
                SCHEMA_VERSION_ATTRIBUTE, attribute(NUMBER_TYPE, SCHEMA_VERSION));
    }

    /**
     * The given correlation id when it satisfies the message schemas, otherwise a new random one.
     *
     * @param correlationId candidate correlation id, may be {@code null}
     * @return a correlation id matching {@link #CORRELATION_ID_PATTERN}
     */
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
