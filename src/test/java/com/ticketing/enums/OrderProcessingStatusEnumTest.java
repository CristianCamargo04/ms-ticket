package com.ticketing.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderProcessingStatusEnumTest {
    @ParameterizedTest(name = "{0} terminal={1}")
    @CsvSource({
            "QUEUED,           false",
            "ACTIVE,           false",
            "COMPLETED,        true",
            "REJECTED,         true",
            "EXPIRED,          true",
            "CANCELLED,        true",
            "PAYMENT_DECLINED, true",
            "FAILED,           true"
    })
    void shouldClassifyTerminalStatuses(OrderProcessingStatusEnum status, boolean terminal) {
        assertThat(status.isTerminal()).isEqualTo(terminal);
    }

    @Test
    void shouldDeclareEveryStatusOfTheContract() {
        assertThat(OrderProcessingStatusEnum.values()).containsExactly(
                OrderProcessingStatusEnum.QUEUED, OrderProcessingStatusEnum.ACTIVE,
                OrderProcessingStatusEnum.COMPLETED, OrderProcessingStatusEnum.REJECTED,
                OrderProcessingStatusEnum.EXPIRED, OrderProcessingStatusEnum.CANCELLED,
                OrderProcessingStatusEnum.PAYMENT_DECLINED, OrderProcessingStatusEnum.FAILED);
    }
}
