package com.ticketing.client.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SqsQueueNamesTest {

    @Test
    void shouldExposeQueueNames() {
        SqsQueueNames names = new SqsQueueNames("reservation", "payment");

        assertThat(names.orderReservationQueue()).isEqualTo("reservation");
        assertThat(names.orderPaymentQueue()).isEqualTo("payment");
    }

    @Test
    void shouldRejectBlankOrMissingNames() {
        assertThatThrownBy(() -> new SqsQueueNames(" ", "payment")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SqsQueueNames("reservation", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SqsQueueNames(null, "payment")).isInstanceOf(NullPointerException.class);
    }
}
