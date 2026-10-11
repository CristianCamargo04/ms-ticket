package com.ticketing.persistence.mapper;

import static com.ticketing.persistence.PersistenceFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.domain.data.EventData;
import com.ticketing.persistence.entity.EventEntity;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class EventEntityMapperTest {
    private final EventEntityMapper mapper = EventEntityMapper.INSTANCE;

    @Test
    void shouldStoreInstantsAsEpochMillisAndMoneyAsPlainString() {
        EventData event = event();

        Map<String, AttributeValue> item = mapper.toItem(event);

        assertThat(item.get("eventId").s()).isEqualTo(event.eventId());
        assertThat(item.get("name").s()).isEqualTo("Concert");
        assertThat(item.get("venue").s()).isEqualTo("Arena");
        assertThat(item.get("eventDate").n()).isEqualTo(Long.toString(event.eventDate().toEpochMilli()));
        assertThat(item.get("totalCapacity").n()).isEqualTo("100");
        assertThat(item.get("ticketPrice").s()).isEqualTo("49.90");
        assertThat(item.get("currency").s()).isEqualTo("USD");
        assertThat(item.get("status").s()).isEqualTo("ON_SALE");
        assertThat(item.get("availableCount").n()).isEqualTo("90");
        assertThat(item.get("reservedCount").n()).isEqualTo("5");
        assertThat(item.get("pendingCount").n()).isEqualTo("3");
        assertThat(item.get("soldCount").n()).isEqualTo("1");
        assertThat(item.get("complimentaryCount").n()).isEqualTo("1");
        assertThat(item.get("version").n()).isEqualTo("7");
        assertThat(item.get("createdAt").n()).isEqualTo(Long.toString(event.createdAt().toEpochMilli()));
        assertThat(item.get("updatedAt").n()).isEqualTo(Long.toString(event.updatedAt().toEpochMilli()));
        assertThat(item).hasSize(16);
    }

    @Test
    void shouldRoundTrip() {
        EventData event = event();

        assertThat(mapper.fromItem(mapper.toItem(event))).isEqualTo(event);
        assertThat(mapper.toDomain(mapper.toEntity(event))).isEqualTo(event);
    }

    @Test
    void shouldFailClearlyOnACorruptedItem() {
        EventEntity entity = mapper.toEntity(event());
        entity.setAvailableCount(null);

        assertThatThrownBy(() -> mapper.toDomain(entity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("availableCount");
    }
}
