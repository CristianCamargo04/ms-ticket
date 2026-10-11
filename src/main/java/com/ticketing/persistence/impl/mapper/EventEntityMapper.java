package com.ticketing.persistence.impl.mapper;

import static com.ticketing.persistence.impl.support.DynamoAttributes.required;
import static com.ticketing.persistence.impl.support.DynamoAttributes.toEpochMillis;
import static com.ticketing.persistence.impl.support.DynamoAttributes.toInstant;

import com.ticketing.domain.data.EventData;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.persistence.entity.EventEntity;
import com.ticketing.persistence.impl.support.EntitySchemas;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Component
public class EventEntityMapper {
    public EventEntity toEntity(EventData event) {
        EventEntity entity = new EventEntity();
        entity.setEventId(event.eventId());
        entity.setName(event.name());
        entity.setVenue(event.venue());
        entity.setEventDate(toEpochMillis(event.eventDate()));
        entity.setTotalCapacity(event.totalCapacity());
        entity.setTicketPrice(event.ticketPrice().toPlainString());
        entity.setCurrency(event.currency());
        entity.setStatus(event.status().name());
        entity.setAvailableCount(event.availableCount());
        entity.setReservedCount(event.reservedCount());
        entity.setPendingCount(event.pendingCount());
        entity.setSoldCount(event.soldCount());
        entity.setComplimentaryCount(event.complimentaryCount());
        entity.setVersion(event.version());
        entity.setCreatedAt(toEpochMillis(event.createdAt()));
        entity.setUpdatedAt(toEpochMillis(event.updatedAt()));
        return entity;
    }

    public EventData toDomain(EventEntity entity) {
        return new EventData(
                entity.getEventId(),
                entity.getName(),
                entity.getVenue(),
                toInstant(entity.getEventDate()),
                required(entity.getTotalCapacity(), "totalCapacity"),
                new BigDecimal(required(entity.getTicketPrice(), "ticketPrice")),
                entity.getCurrency(),
                EventStatusEnum.valueOf(required(entity.getStatus(), "status")),
                required(entity.getAvailableCount(), "availableCount"),
                required(entity.getReservedCount(), "reservedCount"),
                required(entity.getPendingCount(), "pendingCount"),
                required(entity.getSoldCount(), "soldCount"),
                required(entity.getComplimentaryCount(), "complimentaryCount"),
                required(entity.getVersion(), "version"),
                toInstant(entity.getCreatedAt()),
                toInstant(entity.getUpdatedAt()));
    }

    public Map<String, AttributeValue> toItem(EventData event) {
        return EntitySchemas.EVENT.itemToMap(toEntity(event), true);
    }

    public EventData fromItem(Map<String, AttributeValue> item) {
        return toDomain(EntitySchemas.EVENT.mapToItem(item));
    }
}
