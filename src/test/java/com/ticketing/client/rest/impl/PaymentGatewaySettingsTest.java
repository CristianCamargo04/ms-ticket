package com.ticketing.client.rest.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PaymentGatewaySettingsTest {
    @Test
    void shouldExposeSettings() {
        PaymentGatewaySettings settings = new PaymentGatewaySettings(Duration.ofSeconds(3), 2, Duration.ofMillis(100));

        assertThat(settings.callTimeout()).hasSeconds(3);
        assertThat(settings.maxRetries()).isEqualTo(2);
        assertThat(settings.retryBackoff()).hasMillis(100);
    }

    @Test
    void shouldAllowDisablingRetries() {
        assertThat(new PaymentGatewaySettings(Duration.ofSeconds(1), 0, Duration.ofMillis(1)).maxRetries()).isZero();
    }

    @Test
    void shouldRejectInvalidValues() {
        assertThatThrownBy(() -> new PaymentGatewaySettings(Duration.ZERO, 1, Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentGatewaySettings(Duration.ofSeconds(1), 1, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentGatewaySettings(Duration.ofSeconds(1), -1, Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentGatewaySettings(null, 1, Duration.ofMillis(1)))
                .isInstanceOf(NullPointerException.class);
    }
}
