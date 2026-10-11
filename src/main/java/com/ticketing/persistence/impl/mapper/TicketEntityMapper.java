package com.ticketing.persistence.impl.mapper;

import static com.ticketing.persistence.impl.support.DynamoAttributes.required;
import static com.ticketing.persistence.impl.support.DynamoAttributes.toEpochMillis;
import static com.ticketing.persistence.impl.support.DynamoAttributes.toInstant;

import com.ticketing.domain.data.TicketData;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.persistence.entity.TicketEntity;
import com.ticketing.persistence.impl.support.EntitySchemas;
import java.util.Map;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Component
public class TicketEntityMapper {
    public static String statusKey(TicketStatusEnum status, String ticketId) {
        return status.name() + "#" + ticketId;
    }

    public TicketEntity toEntity(TicketData ticket) {
        TicketEntity entity = new TicketEntity();
        entity.setEventId(ticket.eventId());
        entity.setTicketId(ticket.ticketId());
        entity.setStatus(ticket.status().name());
        entity.setOrderId(ticket.orderId());
        entity.setHoldExpiresAt(toEpochMillis(ticket.holdExpiresAt()));
        entity.setStatusKey(statusKey(ticket.status(), ticket.ticketId()));
        entity.setVersion(ticket.version());
        entity.setUpdatedAt(toEpochMillis(ticket.updatedAt()));
        return entity;
    }

    public TicketData toDomain(TicketEntity entity) {
        return new TicketData(
                entity.getEventId(),
                entity.getTicketId(),
                TicketStatusEnum.valueOf(required(entity.getStatus(), "status")),
                entity.getOrderId(),
                toInstant(entity.getHoldExpiresAt()),
                required(entity.getVersion(), "version"),
                toInstant(entity.getUpdatedAt()));
    }

    public Map<String, AttributeValue> toItem(TicketData ticket) {
        return EntitySchemas.TICKET.itemToMap(toEntity(ticket), true);
    }

    public TicketData fromItem(Map<String, AttributeValue> item) {
        return toDomain(EntitySchemas.TICKET.mapToItem(item));
    }
}
