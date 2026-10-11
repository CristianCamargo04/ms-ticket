package com.ticketing.persistence.impl.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.persistence.entity.DistributedLockEntity;
import com.ticketing.persistence.entity.EventEntity;
import com.ticketing.persistence.entity.OrderEntity;
import com.ticketing.persistence.entity.TicketEntity;
import com.ticketing.persistence.entity.TicketTransitionEntity;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.TableMetadata;

/**
 * The entities define the attribute names of implementation spec 6.1; these tests pin them.
 */
class EntitySchemasTest {

    @Test
    void shouldDescribeTheEventsTable() {
        TableMetadata metadata = EntitySchemas.EVENT.tableMetadata();

        assertThat(metadata.primaryPartitionKey()).isEqualTo("eventId");
        assertThat(metadata.indexPartitionKey(EventEntity.STATUS_EVENT_DATE_INDEX)).isEqualTo("status");
        assertThat(metadata.indexSortKey(EventEntity.STATUS_EVENT_DATE_INDEX)).contains("eventDate");
        assertThat(EntitySchemas.EVENT.attributeNames()).containsExactlyInAnyOrder("eventId", "name", "venue",
                "eventDate", "totalCapacity", "ticketPrice", "currency", "status", "availableCount", "reservedCount",
                "pendingCount", "soldCount", "complimentaryCount", "version", "createdAt", "updatedAt");
    }

    @Test
    void shouldDescribeTheTicketsTable() {
        TableMetadata metadata = EntitySchemas.TICKET.tableMetadata();

        assertThat(metadata.primaryPartitionKey()).isEqualTo("eventId");
        assertThat(metadata.primarySortKey()).contains("ticketId");
        assertThat(metadata.indexPartitionKey(TicketEntity.EVENT_STATUS_KEY_INDEX)).isEqualTo("eventId");
        assertThat(metadata.indexSortKey(TicketEntity.EVENT_STATUS_KEY_INDEX)).contains("statusKey");
        assertThat(EntitySchemas.TICKET.attributeNames()).containsExactlyInAnyOrder("eventId", "ticketId", "status",
                "orderId", "holdExpiresAt", "statusKey", "version", "updatedAt");
    }

    @Test
    void shouldDescribeTheOrdersTable() {
        TableMetadata metadata = EntitySchemas.ORDER.tableMetadata();

        assertThat(metadata.primaryPartitionKey()).isEqualTo("orderId");
        assertThat(metadata.indexPartitionKey(OrderEntity.DEADLINE_INDEX)).isEqualTo("deadlinePartition");
        assertThat(metadata.indexSortKey(OrderEntity.DEADLINE_INDEX)).contains("deadlineAt");
        assertThat(EntitySchemas.ORDER.attributeNames()).containsExactlyInAnyOrder("orderId", "eventId",
                "customerId", "orderType", "quantity", "ticketIds", "ticketStatus", "processingStatus", "reason",
                "requestHash", "holdExpiresAt", "paymentReference", "version", "createdAt", "updatedAt",
                "deadlinePartition", "deadlineAt");
    }

    @Test
    void shouldDescribeTheAuditAndLockTables() {
        assertThat(EntitySchemas.TICKET_TRANSITION.tableMetadata().primaryPartitionKey()).isEqualTo("ticketKey");
        assertThat(EntitySchemas.TICKET_TRANSITION.tableMetadata().primarySortKey()).contains("transitionKey");
        assertThat(EntitySchemas.TICKET_TRANSITION.attributeNames()).containsExactlyInAnyOrder("ticketKey",
                "transitionKey", "eventId", "ticketId", "orderId", "fromStatus", "toStatus", "reason", "actor",
                "occurredAt");
        assertThat(EntitySchemas.DISTRIBUTED_LOCK.tableMetadata().primaryPartitionKey()).isEqualTo("lockName");
        assertThat(EntitySchemas.DISTRIBUTED_LOCK.attributeNames()).containsExactlyInAnyOrder("lockName", "owner",
                "leaseUntil", "expiresAt");
    }

    @Test
    void shouldRoundTripEntitiesThroughItemMaps() {
        DistributedLockEntity lock = new DistributedLockEntity();
        lock.setLockName("sweeper");
        lock.setOwner("instance-1");
        lock.setLeaseUntil(1_000L);
        lock.setExpiresAt(1L);

        DistributedLockEntity copy = EntitySchemas.DISTRIBUTED_LOCK.mapToItem(
                EntitySchemas.DISTRIBUTED_LOCK.itemToMap(lock, true));

        assertThat(copy).usingRecursiveComparison().isEqualTo(lock);
        TicketTransitionEntity transition = new TicketTransitionEntity();
        transition.setTicketKey("e#T-000001");
        assertThat(EntitySchemas.TICKET_TRANSITION.itemToMap(transition, true)).containsOnlyKeys("ticketKey");
        OrderEntity order = new OrderEntity();
        order.setOrderId("o-1");
        order.setTicketIds(List.of("T-000001"));
        assertThat(EntitySchemas.ORDER.mapToItem(EntitySchemas.ORDER.itemToMap(order, true)).getTicketIds())
                .containsExactly("T-000001");
    }
}
