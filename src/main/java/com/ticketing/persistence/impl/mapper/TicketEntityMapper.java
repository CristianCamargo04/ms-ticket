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

/**
 * Converts {@link TicketData} to and from the {@code tickets} item, deriving the {@code statusKey} sort key of the
 * availability index ({@code <STATUS>#<ticketId>}).
 */
@Component
public class TicketEntityMapper {

    /**
     * Sort key of a ticket in the {@code eventId-statusKey-index}.
     *
     * @param status   the ticket status
     * @param ticketId the ticket identifier
     * @return {@code <STATUS>#<ticketId>}
     */
    public static String statusKey(TicketStatusEnum status, String ticketId) {
        return status.name() + "#" + ticketId;
    }

    /**
     * Domain to entity.
     *
     * @param ticket the ticket
     * @return the entity
     */
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

    /**
     * Entity (full item or availability index projection) to domain.
     *
     * @param entity the entity
     * @return the ticket
     * @throws IllegalStateException when a mandatory attribute is missing
     */
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

    /**
     * Domain to low-level item.
     *
     * @param ticket the ticket
     * @return the item (no null attributes)
     */
    public Map<String, AttributeValue> toItem(TicketData ticket) {
        return EntitySchemas.TICKET.itemToMap(toEntity(ticket), true);
    }

    /**
     * Low-level item to domain.
     *
     * @param item the item
     * @return the ticket
     */
    public TicketData fromItem(Map<String, AttributeValue> item) {
        return toDomain(EntitySchemas.TICKET.mapToItem(item));
    }
}
