package com.ticketing.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class TicketStatusEnumTest {
    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
            "AVAILABLE,            AVAILABLE,            false",
            "AVAILABLE,            RESERVED,             true",
            "AVAILABLE,            PENDING_CONFIRMATION, false",
            "AVAILABLE,            SOLD,                 false",
            "AVAILABLE,            COMPLIMENTARY,        true",
            "RESERVED,             AVAILABLE,            true",
            "RESERVED,             RESERVED,             false",
            "RESERVED,             PENDING_CONFIRMATION, true",
            "RESERVED,             SOLD,                 false",
            "RESERVED,             COMPLIMENTARY,        false",
            "PENDING_CONFIRMATION, AVAILABLE,            true",
            "PENDING_CONFIRMATION, RESERVED,             false",
            "PENDING_CONFIRMATION, PENDING_CONFIRMATION, false",
            "PENDING_CONFIRMATION, SOLD,                 true",
            "PENDING_CONFIRMATION, COMPLIMENTARY,        false",
            "SOLD,                 AVAILABLE,            false",
            "SOLD,                 RESERVED,             false",
            "SOLD,                 PENDING_CONFIRMATION, false",
            "SOLD,                 SOLD,                 false",
            "SOLD,                 COMPLIMENTARY,        false",
            "COMPLIMENTARY,        AVAILABLE,            false",
            "COMPLIMENTARY,        RESERVED,             false",
            "COMPLIMENTARY,        PENDING_CONFIRMATION, false",
            "COMPLIMENTARY,        SOLD,                 false",
            "COMPLIMENTARY,        COMPLIMENTARY,        false"
    })
    void shouldAllowOnlyTheDocumentedTransitions(TicketStatusEnum from, TicketStatusEnum to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void shouldAllowExactlySixTransitions() {
        long allowed = Arrays.stream(TicketStatusEnum.values())
                .flatMap(from -> Arrays.stream(TicketStatusEnum.values()).filter(from::canTransitionTo))
                .count();

        assertThat(TicketStatusEnum.values()).hasSize(5);
        assertThat(allowed).isEqualTo(6);
    }

    @ParameterizedTest
    @EnumSource(TicketStatusEnum.class)
    void shouldNeverAllowSelfTransition(TicketStatusEnum status) {
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatusEnum.class, names = {"SOLD", "COMPLIMENTARY"})
    void shouldNotLeaveFinalStatuses(TicketStatusEnum status) {
        assertThat(TicketStatusEnum.values()).noneMatch(status::canTransitionTo);
    }

    @Test
    void shouldRejectNullTarget() {
        assertThatNullPointerException().isThrownBy(() -> TicketStatusEnum.AVAILABLE.canTransitionTo(null));
    }

    @ParameterizedTest(name = "{0}: final={1} held={2} accountable={3}")
    @CsvSource({
            "AVAILABLE,            false, false, false",
            "RESERVED,             false, true,  false",
            "PENDING_CONFIRMATION, false, true,  false",
            "SOLD,                 true,  false, true",
            "COMPLIMENTARY,        true,  false, false"
    })
    void shouldClassifyStatuses(TicketStatusEnum status, boolean isFinal, boolean held, boolean accountable) {
        assertThat(status.isFinal()).isEqualTo(isFinal);
        assertThat(status.isHeld()).isEqualTo(held);
        assertThat(status.isAccountable()).isEqualTo(accountable);
    }
}
