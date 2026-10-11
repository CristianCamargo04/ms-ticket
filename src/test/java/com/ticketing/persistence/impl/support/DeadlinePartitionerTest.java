package com.ticketing.persistence.impl.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.enums.DeadlineTypeEnum;
import org.junit.jupiter.api.Test;

class DeadlinePartitionerTest {

    private final DeadlinePartitioner partitioner =
            new DeadlinePartitioner(new DynamoDbTableNames("e", "t", "o", "tt", "l", 8));

    @Test
    void shouldShardByTheOrderIdHash() {
        String orderId = "order-42";

        assertThat(partitioner.partitionOf(DeadlineTypeEnum.HOLD_EXPIRATION, orderId))
                .isEqualTo("HOLD_EXPIRATION#" + Math.floorMod(orderId.hashCode(), 8));
    }

    @Test
    void shouldKeepTheShardNonNegativeForNegativeHashes() {
        String negativeHash = "polygenelubricants";
        assertThat(negativeHash.hashCode()).isNegative();

        assertThat(partitioner.partitionOf(DeadlineTypeEnum.DISPATCH, negativeHash))
                .isEqualTo("DISPATCH#" + Math.floorMod(negativeHash.hashCode(), 8))
                .doesNotContain("-");
    }

    @Test
    void shouldListEveryPartitionOfAType() {
        assertThat(partitioner.partitionsOf(DeadlineTypeEnum.PAYMENT_CONFIRMATION)).containsExactly(
                "PAYMENT_CONFIRMATION#0", "PAYMENT_CONFIRMATION#1", "PAYMENT_CONFIRMATION#2",
                "PAYMENT_CONFIRMATION#3", "PAYMENT_CONFIRMATION#4", "PAYMENT_CONFIRMATION#5",
                "PAYMENT_CONFIRMATION#6", "PAYMENT_CONFIRMATION#7");
    }

    @Test
    void shouldRejectNullArguments() {
        assertThatThrownBy(() -> partitioner.partitionOf(null, "o")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> partitioner.partitionOf(DeadlineTypeEnum.DISPATCH, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> partitioner.partitionsOf(null)).isInstanceOf(NullPointerException.class);
    }
}
