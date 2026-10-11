package com.ticketing.presentation.impl.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.enums.ErrorCodeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencyKeyResolverTest {
    private final IdempotencyKeyResolver resolver = new IdempotencyKeyResolver();

    @ParameterizedTest
    @ValueSource(strings = {"12345678", "6f1c2a9e-0b7d-4c55-9a51-3f2b8e4d7c10", "ORDER-abc-2026"})
    void shouldAcceptWellFormedKeys(String key) {
        assertThat(resolver.resolve(key)).isEqualTo(key);
    }

    @Test
    void shouldAcceptKeysOfExactly64Characters() {
        String key = "a".repeat(64);

        assertThat(resolver.resolve(key)).isEqualTo(key);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldRequireTheHeader(String key) {
        assertThatThrownBy(() -> resolver.resolve(key))
                .isInstanceOfSatisfying(DomainValidationException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(ErrorCodeEnum.INVALID_IDEMPOTENCY_KEY))
                .hasMessageContaining("required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567", "key with spaces", "key_underscore", "key,other-key", "../../etc/passwd",
        "ñandú-12345"})
    void shouldRejectMalformedKeys(String key) {
        assertThatThrownBy(() -> resolver.resolve(key))
                .isInstanceOfSatisfying(DomainValidationException.class, error -> assertThat(error.errorCode())
                        .isEqualTo(ErrorCodeEnum.INVALID_IDEMPOTENCY_KEY));
    }

    @Test
    void shouldRejectKeysLongerThan64Characters() {
        assertThatThrownBy(() -> resolver.resolve("a".repeat(65))).isInstanceOf(DomainValidationException.class);
    }
}
