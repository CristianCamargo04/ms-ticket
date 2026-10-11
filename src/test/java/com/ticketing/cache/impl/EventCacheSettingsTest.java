package com.ticketing.cache.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class EventCacheSettingsTest {

    @Test
    void shouldExposeSettings() {
        EventCacheSettings settings = new EventCacheSettings(Duration.ofSeconds(2), 100);

        assertThat(settings.ttl()).hasSeconds(2);
        assertThat(settings.maxSize()).isEqualTo(100);
    }

    @Test
    void shouldRejectInvalidValues() {
        assertThatThrownBy(() -> new EventCacheSettings(Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventCacheSettings(Duration.ofSeconds(-1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventCacheSettings(Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventCacheSettings(null, 1)).isInstanceOf(NullPointerException.class);
    }
}
