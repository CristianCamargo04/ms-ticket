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
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.factory.Mappers;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

@Mapper(
        componentModel = "spring",
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface SqsOrderQueueClientMapper {

    SqsOrderQueueClientMapper INSTANCE = Mappers.getMapper(SqsOrderQueueClientMapper.class);

    String MESSAGE_TYPE_ATTRIBUTE = "messageType";
    String CORRELATION_ID_ATTRIBUTE = "correlationId";
    String SCHEMA_VERSION_ATTRIBUTE = "schemaVersion";
    String SCHEMA_VERSION = "1";
    String STRING_TYPE = "String";
    String NUMBER_TYPE = "Number";
    Pattern CORRELATION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    @Mapping(target = "correlationId",
            expression = "java(SqsOrderQueueClientMapper.correlationIdOrNew(message.correlationId()))")
    OrderReservationMessageDto toDto(OrderReservationMessage message);

    @Mapping(target = "correlationId",
            expression = "java(SqsOrderQueueClientMapper.correlationIdOrNew(message.correlationId()))")
    PaymentRequestedMessageDto toDto(PaymentRequestedMessage message);

    default Map<String, MessageAttributeValue> toMessageAttributes(MessageTypeEnum messageType, String correlationId) {
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(correlationId, "correlationId");
        return Map.of(
                MESSAGE_TYPE_ATTRIBUTE, attribute(STRING_TYPE, messageType.name()),
                CORRELATION_ID_ATTRIBUTE, attribute(STRING_TYPE, correlationId),
                SCHEMA_VERSION_ATTRIBUTE, attribute(NUMBER_TYPE, SCHEMA_VERSION));
    }

    @Named("correlationIdOrNew")
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
