package com.ticketing.presentation.impl.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.enums.ErrorCodeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ResourceIdValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"e-1", "T-000042", "6f1c2a9e-0b7d-4c55-9a1e-2b3c4d5e6f70", "A"})
    void shouldAcceptTheIdentifiersTheServiceIssues(String id) {
        assertThat(ResourceIdValidator.requireValid(ResourceIdValidator.EVENT_ID, id)).isSameAs(id);
    }

    @Test
    void shouldAcceptIdentifiersOfUpToSixtyFourCharacters() {
        String id = "a".repeat(64);

        assertThat(ResourceIdValidator.requireValid(ResourceIdValidator.ORDER_ID, id)).isEqualTo(id);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"e_1", "e 1", "e.1", "e/1", "évent", "e-1\n", "' OR 1=1"})
    void shouldRejectMissingOrMalformedIdentifiers(String id) {
        assertThatThrownBy(() -> ResourceIdValidator.requireValid(ResourceIdValidator.TICKET_ID, id))
                .isInstanceOfSatisfying(DomainValidationException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCodeEnum.VALIDATION_ERROR);
                    assertThat(exception.retryable()).isFalse();
                })
                .hasMessage("The path parameter 'ticketId' must contain 1 to 64 letters, digits or dashes");
    }

    @Test
    void shouldRejectOversizedIdentifiersWithoutEchoingThem() {
        String id = "a".repeat(65);

        assertThatThrownBy(() -> ResourceIdValidator.requireValid(ResourceIdValidator.EVENT_ID, id))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("'eventId'")
                .hasMessageNotContaining(id);
    }
}
