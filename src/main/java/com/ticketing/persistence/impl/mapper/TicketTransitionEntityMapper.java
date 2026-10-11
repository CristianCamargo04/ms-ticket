package com.ticketing.persistence.impl.mapper;

import static com.ticketing.persistence.impl.support.DynamoAttributes.required;
import static com.ticketing.persistence.impl.support.DynamoAttributes.toEpochMillis;
import static com.ticketing.persistence.impl.support.DynamoAttributes.toInstant;

import com.ticketing.domain.data.TicketTransitionData;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import com.ticketing.persistence.entity.TicketTransitionEntity;
import com.ticketing.persistence.impl.support.EntitySchemas;
import java.util.Map;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Component
public class TicketTransitionEntityMapper {
    private static final String TRANSITION_KEY_FORMAT = "%013d#%s";

    public static String ticketKey(String eventId, String ticketId) {
        return eventId + "#" + ticketId;
    }

    public TicketTransitionEntity toEntity(TicketTransitionData transition) {
        TicketTransitionEntity entity = new TicketTransitionEntity();
        entity.setTicketKey(ticketKey(transition.eventId(), transition.ticketId()));
        entity.setTransitionKey(TRANSITION_KEY_FORMAT.formatted(transition.occurredAt().toEpochMilli(),
                transition.transitionId()));
        entity.setEventId(transition.eventId());
        entity.setTicketId(transition.ticketId());
        entity.setOrderId(transition.orderId());
        entity.setFromStatus(transition.fromStatus().name());
        entity.setToStatus(transition.toStatus().name());
        entity.setReason(transition.reason().name());
        entity.setActor(transition.actor());
        entity.setOccurredAt(toEpochMillis(transition.occurredAt()));
        return entity;
    }

    public TicketTransitionData toDomain(TicketTransitionEntity entity) {
        String transitionKey = required(entity.getTransitionKey(), "transitionKey");
        int separator = transitionKey.indexOf('#');
        if (separator < 0) {
            throw new IllegalStateException("Malformed transitionKey: " + transitionKey);
        }
        return new TicketTransitionData(
                entity.getEventId(),
                entity.getTicketId(),
                transitionKey.substring(separator + 1),
                TicketStatusEnum.valueOf(required(entity.getFromStatus(), "fromStatus")),
                TicketStatusEnum.valueOf(required(entity.getToStatus(), "toStatus")),
                entity.getOrderId(),
                TransitionReasonEnum.valueOf(required(entity.getReason(), "reason")),
                entity.getActor(),
                toInstant(entity.getOccurredAt()));
    }

    public Map<String, AttributeValue> toItem(TicketTransitionData transition) {
        return EntitySchemas.TICKET_TRANSITION.itemToMap(toEntity(transition), true);
    }

    public TicketTransitionData fromItem(Map<String, AttributeValue> item) {
        return toDomain(EntitySchemas.TICKET_TRANSITION.mapToItem(item));
    }
}
