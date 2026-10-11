package com.ticketing.persistence.impl.support;

import com.ticketing.persistence.entity.DistributedLockEntity;
import com.ticketing.persistence.entity.EventEntity;
import com.ticketing.persistence.entity.OrderEntity;
import com.ticketing.persistence.entity.TicketEntity;
import com.ticketing.persistence.entity.TicketTransitionEntity;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

/**
 * Bean table schemas of the entities, built once (introspection is expensive) and shared by the mappers and the
 * enhanced client. A schema converts an entity to the low-level item map ({@code itemToMap}) and back
 * ({@code mapToItem}), so the attribute names are defined in exactly one place: the entity classes.
 */
public final class EntitySchemas {

    /** Schema of the {@code events} table. */
    public static final TableSchema<EventEntity> EVENT = TableSchema.fromBean(EventEntity.class);

    /** Schema of the {@code tickets} table. */
    public static final TableSchema<TicketEntity> TICKET = TableSchema.fromBean(TicketEntity.class);

    /** Schema of the {@code orders} table. */
    public static final TableSchema<OrderEntity> ORDER = TableSchema.fromBean(OrderEntity.class);

    /** Schema of the {@code ticket-transitions} table. */
    public static final TableSchema<TicketTransitionEntity> TICKET_TRANSITION =
            TableSchema.fromBean(TicketTransitionEntity.class);

    /** Schema of the {@code distributed-locks} table. */
    public static final TableSchema<DistributedLockEntity> DISTRIBUTED_LOCK =
            TableSchema.fromBean(DistributedLockEntity.class);

    private EntitySchemas() {
    }
}
