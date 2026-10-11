package com.ticketing.persistence.mapper;

import static com.ticketing.persistence.impl.support.DynamoAttributes.required;

import com.ticketing.domain.data.TicketData;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.persistence.entity.TicketEntity;
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
public interface TicketEntityMapper {

    TicketEntityMapper INSTANCE = Mappers.getMapper(TicketEntityMapper.class);

    @Named("statusKey")
    static String statusKey(TicketStatusEnum status, String ticketId) {
        return status.name() + "#" + ticketId;
    }

    @Mapping(target = "statusKey", expression = "java(TicketEntityMapper.statusKey(ticket.status(), ticket.ticketId()))")
    TicketEntity toEntity(TicketData ticket);

    TicketData toDomain(TicketEntity entity);

    @BeforeMapping
    default void requireAttributes(TicketEntity entity) {
        required(entity.getStatus(), "status");
        required(entity.getVersion(), "version");
    }

    default Map<String, AttributeValue> toItem(TicketData ticket) {
        return EntitySchemas.TICKET.itemToMap(toEntity(ticket), true);
    }

    default TicketData fromItem(Map<String, AttributeValue> item) {
        return toDomain(EntitySchemas.TICKET.mapToItem(item));
    }
}
