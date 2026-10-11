package com.ticketing.persistence.mapper;

import static com.ticketing.persistence.impl.support.DynamoAttributes.required;

import com.ticketing.domain.data.TicketTransitionData;
import com.ticketing.persistence.entity.TicketTransitionEntity;
import com.ticketing.persistence.impl.support.DynamoAttributes;
import com.ticketing.persistence.impl.support.EntitySchemas;
import java.util.Map;
import org.mapstruct.BeforeMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.factory.Mappers;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Mapper(
        componentModel = "spring",
        uses = DynamoAttributes.class,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface TicketTransitionEntityMapper {

    TicketTransitionEntityMapper INSTANCE = Mappers.getMapper(TicketTransitionEntityMapper.class);

    String TRANSITION_KEY_FORMAT = "%013d#%s";

    static String ticketKey(String eventId, String ticketId) {
        return eventId + "#" + ticketId;
    }

    @Mapping(target = "ticketKey",
            expression = "java(TicketTransitionEntityMapper.ticketKey(transition.eventId(), transition.ticketId()))")
    @Mapping(target = "transitionKey", expression = "java(transitionKeyOf(transition))")
    TicketTransitionEntity toEntity(TicketTransitionData transition);

    @Mapping(target = "transitionId", expression = "java(transitionIdOf(entity.getTransitionKey()))")
    TicketTransitionData toDomain(TicketTransitionEntity entity);

    @BeforeMapping
    default void requireAttributes(TicketTransitionEntity entity) {
        required(entity.getFromStatus(), "fromStatus");
        required(entity.getToStatus(), "toStatus");
        required(entity.getReason(), "reason");
    }

    @Named("transitionKeyOf")
    default String transitionKeyOf(TicketTransitionData transition) {
        return TRANSITION_KEY_FORMAT.formatted(transition.occurredAt().toEpochMilli(), transition.transitionId());
    }

    @Named("transitionIdOf")
    default String transitionIdOf(String transitionKey) {
        String key = required(transitionKey, "transitionKey");
        int separator = key.indexOf('#');
        if (separator < 0) {
            throw new IllegalStateException("Malformed transitionKey: " + key);
        }
        return key.substring(separator + 1);
    }

    default Map<String, AttributeValue> toItem(TicketTransitionData transition) {
        return EntitySchemas.TICKET_TRANSITION.itemToMap(toEntity(transition), true);
    }

    default TicketTransitionData fromItem(Map<String, AttributeValue> item) {
        return toDomain(EntitySchemas.TICKET_TRANSITION.mapToItem(item));
    }
}
