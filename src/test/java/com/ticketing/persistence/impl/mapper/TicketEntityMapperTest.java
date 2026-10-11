package com.ticketing.persistence.impl.mapper;

import static com.ticketing.persistence.PersistenceFixtures.EVENT_ID;
import static com.ticketing.persistence.PersistenceFixtures.NOW;
import static com.ticketing.persistence.PersistenceFixtures.ORDER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.domain.data.TicketData;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.persistence.entity.TicketEntity;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class TicketEntityMapperTest {
    private final TicketEntityMapper mapper = new TicketEntityMapper();

    @Test
    void shouldDeriveTheAvailabilityIndexSortKey() {
        assertThat(TicketEntityMapper.statusKey(TicketStatusEnum.AVAILABLE, "T-000007")).isEqualTo("AVAILABLE#T-000007");
        assertThat(TicketEntityMapper.statusKey(TicketStatusEnum.SOLD, "T-000007")).isEqualTo("SOLD#T-000007");
    }

    @Test
    void shouldOmitTheOrderAndHoldOfAnAvailableTicket() {
        Map<String, AttributeValue> item = mapper.toItem(TicketData.available(EVENT_ID, "T-000001", NOW));

        assertThat(item).containsOnlyKeys("eventId", "ticketId", "status", "statusKey", "version", "updatedAt");
        assertThat(item.get("statusKey").s()).isEqualTo("AVAILABLE#T-000001");
        assertThat(item.get("updatedAt").n()).isEqualTo(Long.toString(NOW.toEpochMilli()));
    }

    @Test
    void shouldRoundTripAHeldTicket() {
        TicketData held = new TicketData(EVENT_ID, "T-000002", TicketStatusEnum.RESERVED, ORDER_ID,
                NOW.plusSeconds(600), 4, NOW);

        Map<String, AttributeValue> item = mapper.toItem(held);

        assertThat(item.get("orderId").s()).isEqualTo(ORDER_ID);
        assertThat(item.get("holdExpiresAt").n()).isEqualTo(Long.toString(NOW.plusSeconds(600).toEpochMilli()));
        assertThat(item.get("statusKey").s()).isEqualTo("RESERVED#T-000002");
        assertThat(mapper.fromItem(item)).isEqualTo(held);
    }

    @Test
    void shouldReadTheAvailabilityIndexProjection() {
        Map<String, AttributeValue> projection = Map.of(
                "eventId", AttributeValue.fromS(EVENT_ID),
                "ticketId", AttributeValue.fromS("T-000003"),
                "statusKey", AttributeValue.fromS("AVAILABLE#T-000003"),
                "status", AttributeValue.fromS("AVAILABLE"),
                "version", AttributeValue.fromN("1"),
                "updatedAt", AttributeValue.fromN(Long.toString(NOW.toEpochMilli())));

        assertThat(mapper.fromItem(projection)).isEqualTo(TicketData.available(EVENT_ID, "T-000003", NOW));
    }

    @Test
    void shouldFailClearlyOnACorruptedItem() {
        TicketEntity entity = mapper.toEntity(TicketData.available(EVENT_ID, "T-000001", NOW));
        entity.setStatus(null);

        assertThatThrownBy(() -> mapper.toDomain(entity)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status");
    }
}
