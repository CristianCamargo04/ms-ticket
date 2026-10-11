package com.ticketing.persistence.impl.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.domain.exception.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

class CursorCodecTest {
    private static final Map<String, ScalarAttributeType> KEY_SCHEMA = Map.of(
            "eventId", ScalarAttributeType.S,
            "status", ScalarAttributeType.S,
            "eventDate", ScalarAttributeType.N);

    private static final Map<String, AttributeValue> KEY = Map.of(
            "eventId", AttributeValue.fromS("id with spaces & = : / ?"),
            "status", AttributeValue.fromS("ON_SALE"),
            "eventDate", AttributeValue.fromN("1791633600123"));

    @Test
    void shouldRoundTripALastEvaluatedKey() {
        String cursor = CursorCodec.encode(KEY);

        assertThat(cursor).matches("[A-Za-z0-9_-]+");
        assertThat(CursorCodec.decode(cursor, KEY_SCHEMA)).isEqualTo(KEY);
    }

    @Test
    void shouldEncodeDeterministically() {
        assertThat(CursorCodec.encode(KEY)).isEqualTo(CursorCodec.encode(Map.copyOf(KEY)));
    }

    @Test
    void shouldReturnNoCursorWithoutAKey() {
        assertThat(CursorCodec.encode(null)).isNull();
        assertThat(CursorCodec.encode(Map.of())).isNull();
    }

    @Test
    void shouldRejectKeysThatAreNeitherStringsNorNumbers() {
        Map<String, AttributeValue> binary = Map.of("eventId", AttributeValue.fromB(SdkBytes.fromUtf8String("x")));

        assertThatThrownBy(() -> CursorCodec.encode(binary)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldTreatAMissingCursorAsTheFirstPage() {
        assertThat(CursorCodec.decode(null, KEY_SCHEMA)).isEmpty();
        assertThat(CursorCodec.decode("  ", KEY_SCHEMA)).isEmpty();
    }

    @Test
    void shouldNormalizeIntegralNumbers() {
        String cursor = encodeRaw("eventDate=N:000150&eventId=S:a&status=S:ON_SALE");

        assertThat(CursorCodec.decode(cursor, KEY_SCHEMA).get("eventDate")).isEqualTo(AttributeValue.fromN("150"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-9223372036854775808", "9223372036854775807", "0", "-1"})
    void shouldAcceptEveryLongNumber(String number) {
        String cursor = encodeRaw("eventDate=N:" + number + "&eventId=S:a&status=S:ON_SALE");

        assertThat(CursorCodec.decode(cursor, KEY_SCHEMA).get("eventDate")).isEqualTo(AttributeValue.fromN(number));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e999999999", "1E999999999", "1e60", "1.5", "1.50E2", "150.0", "+150", " 150", "",
            "-", "9223372036854775808", "-9223372036854775809", "12345678901234567890", "%EF%BC%91"})
    void shouldRejectNumbersThatAreNotPlainLongs(String number) {
        String cursor = encodeRaw("eventDate=N:" + number + "&eventId=S:a&status=S:ON_SALE");

        assertThatThrownBy(() -> CursorCodec.decode(cursor, KEY_SCHEMA))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("Malformed pagination cursor");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "eventDate=N:1&eventId=S:a",
            "eventDate=N:1&eventId=S:a&status=S:ON_SALE&extra=S:x",
            "eventDate=N:1&eventId=S:a&status=S:ON_SALE&status=S:ON_SALE",
            "eventDate=S:1&eventId=S:a&status=S:ON_SALE",
            "eventDate=N:not-a-number&eventId=S:a&status=S:ON_SALE",
            "eventDate=N:1&eventId=S:a&status",
            "eventDate=N:1&eventId=SS:a&status=S:x",
            "=S:a",
            "eventDate=N:1&eventId=S:%zz&status=S:x",
            "&"
    })
    void shouldRejectMalformedPayloads(String payload) {
        String cursor = encodeRaw(payload);

        assertThatThrownBy(() -> CursorCodec.decode(cursor, KEY_SCHEMA))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("Malformed pagination cursor");
    }

    @Test
    void shouldRejectInvalidBase64() {
        assertThatThrownBy(() -> CursorCodec.decode("***", KEY_SCHEMA))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void shouldRejectOversizedCursors() {
        String cursor = "A".repeat(CursorCodec.MAX_CURSOR_LENGTH + 1);

        assertThatThrownBy(() -> CursorCodec.decode(cursor, KEY_SCHEMA))
                .isInstanceOf(DomainValidationException.class);
    }

    private static String encodeRaw(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
}
