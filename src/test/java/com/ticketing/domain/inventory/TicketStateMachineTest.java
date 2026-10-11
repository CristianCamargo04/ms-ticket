package com.ticketing.domain.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.domain.exception.InvalidTicketTransitionException;
import com.ticketing.enums.ErrorCodeEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class TicketStateMachineTest {

    static Stream<Arguments> everyStatusPair() {
        return Arrays.stream(TicketStatusEnum.values())
                .flatMap(from -> Arrays.stream(TicketStatusEnum.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyStatusPair")
    void shouldAcceptExactlyTheTransitionsOfTheMatrix(TicketStatusEnum from, TicketStatusEnum to) {
        if (from.canTransitionTo(to)) {
            assertThatCode(() -> TicketStateMachine.ensureTransition(from, to)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> TicketStateMachine.ensureTransition(from, to))
                    .isInstanceOfSatisfying(InvalidTicketTransitionException.class, exception -> {
                        assertThat(exception.from()).isEqualTo(from);
                        assertThat(exception.to()).isEqualTo(to);
                        assertThat(exception.errorCode()).isEqualTo(ErrorCodeEnum.INVALID_TICKET_TRANSITION);
                    });
        }
    }

    @Test
    void shouldRejectNullStatuses() {
        assertThatNullPointerException()
                .isThrownBy(() -> TicketStateMachine.ensureTransition(null, TicketStatusEnum.RESERVED));
        assertThatNullPointerException()
                .isThrownBy(() -> TicketStateMachine.ensureTransition(TicketStatusEnum.AVAILABLE, null));
    }

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @CsvSource({
            "RESERVATION,           AVAILABLE,            RESERVED",
            "COMPLIMENTARY_ISSUED,  AVAILABLE,            COMPLIMENTARY",
            "CUSTOMER_CONFIRMATION, RESERVED,             PENDING_CONFIRMATION",
            "PAYMENT_APPROVED,      PENDING_CONFIRMATION, SOLD",
            "PAYMENT_DECLINED,      PENDING_CONFIRMATION, AVAILABLE",
            "HOLD_EXPIRED,          RESERVED,             AVAILABLE",
            "CONFIRMATION_TIMEOUT,  PENDING_CONFIRMATION, AVAILABLE",
            "CUSTOMER_CANCELLATION, RESERVED,             AVAILABLE"
    })
    void shouldMapEveryReasonToItsTransition(TransitionReasonEnum reason, TicketStatusEnum source,
                                             TicketStatusEnum target) {
        assertThat(TicketStateMachine.sourceOf(reason)).isEqualTo(source);
        assertThat(TicketStateMachine.targetOf(reason)).isEqualTo(target);
    }

    @ParameterizedTest
    @EnumSource(TransitionReasonEnum.class)
    void shouldOnlyMapReasonsToAllowedTransitions(TransitionReasonEnum reason) {
        assertThat(TicketStateMachine.sourceOf(reason).canTransitionTo(TicketStateMachine.targetOf(reason))).isTrue();
    }
}
