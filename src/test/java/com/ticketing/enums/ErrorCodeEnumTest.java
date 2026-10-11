package com.ticketing.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ErrorCodeEnumTest {

    @ParameterizedTest(name = "{0} -> {1} retryable={2}")
    @CsvSource({
            "VALIDATION_ERROR,            400, false",
            "INVALID_IDEMPOTENCY_KEY,     400, false",
            "UNAUTHORIZED,                401, false",
            "FORBIDDEN,                   403, false",
            "EVENT_NOT_FOUND,             404, false",
            "ORDER_NOT_FOUND,             404, false",
            "EVENT_NOT_ON_SALE,           409, false",
            "SOLD_OUT,                    409, false",
            "INVALID_ORDER_STATE,         409, false",
            "INVALID_TICKET_TRANSITION,   409, false",
            "HOLD_EXPIRED,                410, false",
            "IDEMPOTENCY_KEY_REUSED,      422, false",
            "RATE_LIMIT_EXCEEDED,         429, false",
            "INTERNAL_ERROR,              500, false",
            "INVENTORY_CONFLICT,          503, true",
            "MESSAGE_PUBLISHING_FAILED,   503, true",
            "PAYMENT_GATEWAY_UNAVAILABLE, 503, true"
    })
    void shouldMapEveryCodeToItsHttpStatusAndRetrySemantics(ErrorCodeEnum code, int httpStatus, boolean retryable) {
        assertThat(code.httpStatus()).isEqualTo(httpStatus);
        assertThat(code.retryable()).isEqualTo(retryable);
    }

    @Test
    void shouldDeclareExactlyTheCodesOfTheContract() {
        assertThat(ErrorCodeEnum.values()).hasSize(17);
    }
}
