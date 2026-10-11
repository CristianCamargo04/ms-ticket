package com.ticketing.persistence.impl.mapper;

import static com.ticketing.persistence.PersistenceFixtures.EVENT_ID;
import static com.ticketing.persistence.PersistenceFixtures.NOW;
import static com.ticketing.persistence.PersistenceFixtures.ORDER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.domain.data.TicketTransitionData;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import com.ticketing.persistence.entity.TicketTransitionEntity;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class TicketTransitionEntityMapperTest {
    private static final TicketTransitionData TRANSITION = new TicketTransitionData(EVENT_ID, "T-000009",
            "transition-1", TicketStatusEnum.AVAILABLE, TicketStatusEnum.RESERVED, ORDER_ID,
            TransitionReasonEnum.RESERVATION, "order-worker", NOW);

    private final TicketTransitionEntityMapper mapper = new TicketTransitionEntityMapper();

    @Test
    void shouldKeyTheHistoryByTicketAndChronologicalSortKey() {
        Map<String, AttributeValue> item = mapper.toItem(TRANSITION);

        assertThat(item.get("ticketKey").s()).isEqualTo(EVENT_ID + "#T-000009");
        assertThat(item.get("transitionKey").s()).isEqualTo("1791633600123#transition-1");
        assertThat(item.get("fromStatus").s()).isEqualTo("AVAILABLE");
        assertThat(item.get("toStatus").s()).isEqualTo("RESERVED");
        assertThat(item.get("reason").s()).isEqualTo("RESERVATION");
        assertThat(item.get("actor").s()).isEqualTo("order-worker");
        assertThat(item.get("occurredAt").n()).isEqualTo("1791633600123");
        assertThat(TicketTransitionEntityMapper.ticketKey("e", "t")).isEqualTo("e#t");
    }

    @Test
    void shouldZeroPadTheSortKeySoThatItSortsChronologically() {
        TicketTransitionData early = new TicketTransitionData(EVENT_ID, "T-000009", "x", TicketStatusEnum.AVAILABLE,
                TicketStatusEnum.RESERVED, ORDER_ID, TransitionReasonEnum.RESERVATION, "a", Instant.ofEpochMilli(5));

        assertThat(mapper.toEntity(early).getTransitionKey()).isEqualTo("0000000000005#x");
    }

    @Test
    void shouldRoundTripAndRecoverTheTransitionId() {
        TicketTransitionData withHashInId = new TicketTransitionData(EVENT_ID, "T-1", "id#with#hashes",
                TicketStatusEnum.RESERVED, TicketStatusEnum.AVAILABLE, ORDER_ID, TransitionReasonEnum.HOLD_EXPIRED,
                "sweeper", NOW);

        assertThat(mapper.fromItem(mapper.toItem(TRANSITION))).isEqualTo(TRANSITION);
        assertThat(mapper.fromItem(mapper.toItem(withHashInId))).isEqualTo(withHashInId);
    }

    @Test
    void shouldRejectAMalformedSortKey() {
        TicketTransitionEntity entity = mapper.toEntity(TRANSITION);
        entity.setTransitionKey("no-separator");

        assertThatThrownBy(() -> mapper.toDomain(entity)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transitionKey");
        entity.setTransitionKey(null);
        assertThatThrownBy(() -> mapper.toDomain(entity)).isInstanceOf(IllegalStateException.class);
    }
}
