package com.ticketing.domain.inventory;

import static com.ticketing.domain.inventory.OrderFixtures.reserved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.domain.data.OrderData;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InventoryWriteOutcomeTest {
    private static final OrderData ORDER = reserved();

    private static String describe(InventoryWriteOutcome outcome) {
        return switch (outcome) {
            case InventoryWriteOutcome.Applied(OrderData order) -> "applied:" + order.orderId();
            case InventoryWriteOutcome.InsufficientInventory() -> "sold-out";
            case InventoryWriteOutcome.StaleTicketSelection() -> "retry";
            case InventoryWriteOutcome.OrderStateChanged() -> "reload";
        };
    }

    static Stream<Arguments> everyOutcome() {
        return Stream.of(
                Arguments.of(new InventoryWriteOutcome.Applied(ORDER), "applied:o-1"),
                Arguments.of(new InventoryWriteOutcome.InsufficientInventory(), "sold-out"),
                Arguments.of(new InventoryWriteOutcome.StaleTicketSelection(), "retry"),
                Arguments.of(new InventoryWriteOutcome.OrderStateChanged(), "reload"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("everyOutcome")
    void shouldBeExhaustivelyPatternMatchable(InventoryWriteOutcome outcome, String expected) {
        assertThat(describe(outcome)).isEqualTo(expected);
    }

    @Test
    void shouldCarryTheUpdatedOrderWhenApplied() {
        InventoryWriteOutcome.Applied applied = new InventoryWriteOutcome.Applied(ORDER);

        assertThat(applied.order()).isSameAs(ORDER);
        assertThatNullPointerException().isThrownBy(() -> new InventoryWriteOutcome.Applied(null));
    }

    @Test
    void shouldTreatConflictOutcomesAsValues() {
        assertThat(new InventoryWriteOutcome.InsufficientInventory())
                .isEqualTo(new InventoryWriteOutcome.InsufficientInventory());
        assertThat(new InventoryWriteOutcome.StaleTicketSelection())
                .isEqualTo(new InventoryWriteOutcome.StaleTicketSelection());
        assertThat(new InventoryWriteOutcome.OrderStateChanged())
                .isEqualTo(new InventoryWriteOutcome.OrderStateChanged());
    }
}
