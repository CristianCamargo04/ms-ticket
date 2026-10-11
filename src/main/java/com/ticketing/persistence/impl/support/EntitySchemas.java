package com.ticketing.persistence.impl.support;

import com.ticketing.persistence.entity.DistributedLockEntity;
import com.ticketing.persistence.entity.EventEntity;
import com.ticketing.persistence.entity.OrderEntity;
import com.ticketing.persistence.entity.TicketEntity;
import com.ticketing.persistence.entity.TicketTransitionEntity;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

public final class EntitySchemas {
    public static final TableSchema<EventEntity> EVENT = TableSchema.fromBean(EventEntity.class);

    public static final TableSchema<TicketEntity> TICKET = TableSchema.fromBean(TicketEntity.class);

    public static final TableSchema<OrderEntity> ORDER = TableSchema.fromBean(OrderEntity.class);

    public static final TableSchema<TicketTransitionEntity> TICKET_TRANSITION =
            TableSchema.fromBean(TicketTransitionEntity.class);

    public static final TableSchema<DistributedLockEntity> DISTRIBUTED_LOCK =
            TableSchema.fromBean(DistributedLockEntity.class);

    private EntitySchemas() {
    }
}
