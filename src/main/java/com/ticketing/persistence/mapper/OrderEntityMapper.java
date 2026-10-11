package com.ticketing.persistence.mapper;

import static com.ticketing.persistence.impl.support.DynamoAttributes.required;
import static com.ticketing.persistence.impl.support.DynamoAttributes.toEpochMillis;

import com.ticketing.domain.data.OrderData;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.persistence.entity.OrderEntity;
import com.ticketing.persistence.impl.support.DeadlinePartitioner;
import com.ticketing.persistence.impl.support.DynamoAttributes;
import com.ticketing.persistence.impl.support.EntitySchemas;
import java.util.Map;
import org.mapstruct.AfterMapping;
import org.mapstruct.BeforeMapping;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.factory.Mappers;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Mapper(
        componentModel = "spring",
        uses = DynamoAttributes.class,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface OrderEntityMapper {

    OrderEntityMapper INSTANCE = Mappers.getMapper(OrderEntityMapper.class);

    @Mapping(target = "orderType", source = "type")
    @Mapping(target = "deadlinePartition", ignore = true)
    @Mapping(target = "deadlineAt", ignore = true)
    OrderEntity toEntity(OrderData order, @Context DeadlinePartitioner partitioner);

    @Mapping(target = "type", source = "orderType")
    @Mapping(target = "ticketIds", source = "ticketIds", defaultExpression = "java(java.util.List.of())")
    OrderData toDomain(OrderEntity entity);

    @AfterMapping
    default void applyDeadline(OrderData order, @MappingTarget OrderEntity entity,
                               @Context DeadlinePartitioner partitioner) {
        DeadlineTypeEnum deadlineType = order.deadlineType();
        if (deadlineType != null && order.deadlineAt() != null) {
            entity.setDeadlinePartition(partitioner.partitionOf(deadlineType, order.orderId()));
            entity.setDeadlineAt(toEpochMillis(order.deadlineAt()));
        }
    }

    @BeforeMapping
    default void requireAttributes(OrderEntity entity) {
        required(entity.getOrderType(), "orderType");
        required(entity.getQuantity(), "quantity");
        required(entity.getTicketStatus(), "ticketStatus");
        required(entity.getProcessingStatus(), "processingStatus");
        required(entity.getVersion(), "version");
    }

    default Map<String, AttributeValue> toItem(OrderData order, DeadlinePartitioner partitioner) {
        return EntitySchemas.ORDER.itemToMap(toEntity(order, partitioner), true);
    }

    default OrderData fromItem(Map<String, AttributeValue> item) {
        return toDomain(EntitySchemas.ORDER.mapToItem(item));
    }
}
