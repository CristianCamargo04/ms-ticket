package com.ticketing.persistence.mapper;

import static com.ticketing.persistence.PersistenceFixtures.NOW;
import static com.ticketing.persistence.PersistenceFixtures.ORDER_ID;
import static com.ticketing.persistence.PersistenceFixtures.TABLES;
import static com.ticketing.persistence.PersistenceFixtures.pendingOrder;
import static com.ticketing.persistence.PersistenceFixtures.queuedOrder;
import static com.ticketing.persistence.PersistenceFixtures.reservedOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.domain.data.OrderData;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.persistence.entity.OrderEntity;
import com.ticketing.persistence.impl.support.DeadlinePartitioner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class OrderEntityMapperTest {
    private final DeadlinePartitioner partitioner = new DeadlinePartitioner(TABLES);
    private final OrderEntityMapper mapper = OrderEntityMapper.INSTANCE;

    @Test
    void shouldWriteTheDispatchDeadlineOfAQueuedOrder() {
        OrderData order = queuedOrder(OrderTypeEnum.STANDARD, 2);

        Map<String, AttributeValue> item = mapper.toItem(order, partitioner);

        assertThat(item.get("orderId").s()).isEqualTo(ORDER_ID);
        assertThat(item.get("orderType").s()).isEqualTo("STANDARD");
        assertThat(item.get("quantity").n()).isEqualTo("2");
        assertThat(item.get("ticketIds").l()).isEmpty();
        assertThat(item.get("ticketStatus").s()).isEqualTo("AVAILABLE");
        assertThat(item.get("processingStatus").s()).isEqualTo("QUEUED");
        assertThat(item.get("deadlinePartition").s())
                .isEqualTo(partitioner.partitionOf(DeadlineTypeEnum.DISPATCH, ORDER_ID));
        assertThat(item.get("deadlineAt").n()).isEqualTo(Long.toString(order.deadlineAt().toEpochMilli()));
        assertThat(item).doesNotContainKeys("reason", "holdExpiresAt", "paymentReference");
        assertThat(mapper.fromItem(item)).isEqualTo(order);
    }

    @Test
    void shouldWriteTheHoldAndPaymentDeadlines() {
        OrderData reserved = reservedOrder(List.of("T-000001", "T-000002"));
        OrderData pending = pendingOrder(List.of("T-000001"));

        assertThat(mapper.toEntity(reserved, partitioner).getDeadlinePartition())
                .isEqualTo(partitioner.partitionOf(DeadlineTypeEnum.HOLD_EXPIRATION, ORDER_ID));
        assertThat(mapper.toItem(reserved, partitioner).get("ticketIds").l()).extracting(AttributeValue::s)
                .containsExactly("T-000001", "T-000002");
        assertThat(mapper.toEntity(pending, partitioner).getDeadlinePartition())
                .isEqualTo(partitioner.partitionOf(DeadlineTypeEnum.PAYMENT_CONFIRMATION, ORDER_ID));
        assertThat(mapper.fromItem(mapper.toItem(reserved, partitioner))).isEqualTo(reserved);
        assertThat(mapper.fromItem(mapper.toItem(pending, partitioner))).isEqualTo(pending);
    }

    @Test
    void shouldOmitTheDeadlineOfATerminalOrder() {
        OrderData sold = pendingOrder(List.of("T-000001")).withSale("pay-1", NOW);

        Map<String, AttributeValue> item = mapper.toItem(sold, partitioner);

        assertThat(item).doesNotContainKeys("deadlinePartition", "deadlineAt");
        assertThat(item.get("paymentReference").s()).isEqualTo("pay-1");
        assertThat(mapper.fromItem(item)).isEqualTo(sold);
    }

    @Test
    void shouldOmitAnIncompleteDeadline() {
        OrderData queuedWithoutDeadline = queuedOrder(OrderTypeEnum.COMPLIMENTARY, 1).withDeadline(null, NOW);

        assertThat(mapper.toItem(queuedWithoutDeadline, partitioner)).doesNotContainKeys("deadlinePartition", "deadlineAt");
    }

    @Test
    void shouldKeepTheReasonOfARejectedOrder() {
        OrderData rejected = queuedOrder(OrderTypeEnum.STANDARD, 1)
                .withProcessingStatus(OrderProcessingStatusEnum.REJECTED, "SOLD_OUT", NOW);

        assertThat(mapper.toItem(rejected, partitioner).get("reason").s()).isEqualTo("SOLD_OUT");
        assertThat(mapper.fromItem(mapper.toItem(rejected, partitioner))).isEqualTo(rejected);
    }

    @Test
    void shouldReadAMissingTicketListAsEmpty() {
        OrderEntity entity = mapper.toEntity(queuedOrder(OrderTypeEnum.STANDARD, 1), partitioner);
        entity.setTicketIds(null);

        assertThat(mapper.toDomain(entity).ticketIds()).isEmpty();
    }

    @Test
    void shouldFailClearlyOnACorruptedItem() {
        OrderEntity entity = mapper.toEntity(queuedOrder(OrderTypeEnum.STANDARD, 1), partitioner);
        entity.setQuantity(null);

        assertThatThrownBy(() -> mapper.toDomain(entity)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quantity");
    }
}
