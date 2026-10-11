package com.ticketing.persistence.impl.mapper;

import static com.ticketing.persistence.impl.support.DynamoAttributes.required;
import static com.ticketing.persistence.impl.support.DynamoAttributes.toEpochMillis;
import static com.ticketing.persistence.impl.support.DynamoAttributes.toInstant;

import com.ticketing.domain.data.OrderData;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.persistence.entity.OrderEntity;
import com.ticketing.persistence.impl.support.DeadlinePartitioner;
import com.ticketing.persistence.impl.support.EntitySchemas;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Converts {@link OrderData} to and from the {@code orders} item. The sparse deadline attributes
 * ({@code deadlinePartition}, {@code deadlineAt}) are written only while {@link OrderData#deadlineType()} is not
 * {@code null} (implementation spec 6.2).
 */
@Component
public class OrderEntityMapper {

    private final DeadlinePartitioner partitioner;

    /**
     * Creates the mapper.
     *
     * @param partitioner computes the deadline partition of an order
     */
    public OrderEntityMapper(DeadlinePartitioner partitioner) {
        this.partitioner = partitioner;
    }

    /**
     * Domain to entity.
     *
     * @param order the order
     * @return the entity
     */
    public OrderEntity toEntity(OrderData order) {
        OrderEntity entity = new OrderEntity();
        entity.setOrderId(order.orderId());
        entity.setEventId(order.eventId());
        entity.setCustomerId(order.customerId());
        entity.setOrderType(order.type().name());
        entity.setQuantity(order.quantity());
        entity.setTicketIds(order.ticketIds());
        entity.setTicketStatus(order.ticketStatus().name());
        entity.setProcessingStatus(order.processingStatus().name());
        entity.setReason(order.reason());
        entity.setRequestHash(order.requestHash());
        entity.setHoldExpiresAt(toEpochMillis(order.holdExpiresAt()));
        entity.setPaymentReference(order.paymentReference());
        entity.setVersion(order.version());
        entity.setCreatedAt(toEpochMillis(order.createdAt()));
        entity.setUpdatedAt(toEpochMillis(order.updatedAt()));
        DeadlineTypeEnum deadlineType = order.deadlineType();
        if (deadlineType != null && order.deadlineAt() != null) {
            entity.setDeadlinePartition(partitioner.partitionOf(deadlineType, order.orderId()));
            entity.setDeadlineAt(toEpochMillis(order.deadlineAt()));
        }
        return entity;
    }

    /**
     * Entity to domain.
     *
     * @param entity the entity
     * @return the order
     * @throws IllegalStateException when a mandatory attribute is missing
     */
    public OrderData toDomain(OrderEntity entity) {
        List<String> ticketIds = entity.getTicketIds();
        return new OrderData(
                entity.getOrderId(),
                entity.getEventId(),
                entity.getCustomerId(),
                OrderTypeEnum.valueOf(required(entity.getOrderType(), "orderType")),
                required(entity.getQuantity(), "quantity"),
                ticketIds == null ? List.of() : ticketIds,
                TicketStatusEnum.valueOf(required(entity.getTicketStatus(), "ticketStatus")),
                OrderProcessingStatusEnum.valueOf(required(entity.getProcessingStatus(), "processingStatus")),
                entity.getReason(),
                entity.getRequestHash(),
                toInstant(entity.getHoldExpiresAt()),
                toInstant(entity.getDeadlineAt()),
                entity.getPaymentReference(),
                required(entity.getVersion(), "version"),
                toInstant(entity.getCreatedAt()),
                toInstant(entity.getUpdatedAt()));
    }

    /**
     * Domain to low-level item.
     *
     * @param order the order
     * @return the item (no null attributes, so the deadline attributes are absent when there is no deadline)
     */
    public Map<String, AttributeValue> toItem(OrderData order) {
        return EntitySchemas.ORDER.itemToMap(toEntity(order), true);
    }

    /**
     * Low-level item to domain.
     *
     * @param item the item
     * @return the order
     */
    public OrderData fromItem(Map<String, AttributeValue> item) {
        return toDomain(EntitySchemas.ORDER.mapToItem(item));
    }
}
