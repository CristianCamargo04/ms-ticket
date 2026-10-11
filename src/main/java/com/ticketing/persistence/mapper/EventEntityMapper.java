package com.ticketing.persistence.mapper;

import static com.ticketing.persistence.impl.support.DynamoAttributes.required;

import com.ticketing.domain.data.EventData;
import com.ticketing.persistence.entity.EventEntity;
import com.ticketing.persistence.impl.support.DynamoAttributes;
import com.ticketing.persistence.impl.support.EntitySchemas;
import java.math.BigDecimal;
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
public interface EventEntityMapper {

    EventEntityMapper INSTANCE = Mappers.getMapper(EventEntityMapper.class);

    @Mapping(target = "ticketPrice", source = "ticketPrice", qualifiedByName = "plainDecimal")
    EventEntity toEntity(EventData event);

    EventData toDomain(EventEntity entity);

    @BeforeMapping
    default void requireAttributes(EventEntity entity) {
        required(entity.getTotalCapacity(), "totalCapacity");
        required(entity.getTicketPrice(), "ticketPrice");
        required(entity.getStatus(), "status");
        required(entity.getAvailableCount(), "availableCount");
        required(entity.getReservedCount(), "reservedCount");
        required(entity.getPendingCount(), "pendingCount");
        required(entity.getSoldCount(), "soldCount");
        required(entity.getComplimentaryCount(), "complimentaryCount");
        required(entity.getVersion(), "version");
    }

    @Named("plainDecimal")
    default String plainDecimal(BigDecimal value) {
        return value.toPlainString();
    }

    default Map<String, AttributeValue> toItem(EventData event) {
        return EntitySchemas.EVENT.itemToMap(toEntity(event), true);
    }

    default EventData fromItem(Map<String, AttributeValue> item) {
        return toDomain(EntitySchemas.EVENT.mapToItem(item));
    }
}
