package com.ticketing.persistence.impl.support;

import com.ticketing.domain.exception.DomainValidationException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Opaque pagination cursor: the {@code LastEvaluatedKey} of a query encoded as URL-safe Base64.
 *
 * <p>The payload is {@code name=TYPE:value} entries joined by {@code &} (names and values URL-encoded, entries sorted
 * by name). Decoding is strict because the cursor comes from clients: it must be valid Base64, at most
 * {@value #MAX_CURSOR_LENGTH} characters long and contain exactly the key attributes of the queried index with
 * their declared types. Numeric key attributes of this schema are epoch milliseconds, so a numeric value must be a
 * plain integer that fits a {@code long} (at most 19 digits, no sign other than {@code -}, no fraction, no exponent):
 * it is never expanded with {@code BigDecimal}, which would let a tiny cursor such as {@code 1e999999999} allocate a
 * billion-character string, and it is always a number DynamoDB accepts (at most 38 significant digits). Anything else
 * is a {@link DomainValidationException}, so a tampered cursor never reaches DynamoDB as an invalid
 * {@code ExclusiveStartKey}.
 */
public final class CursorCodec {

    /** Upper bound of an accepted cursor (a real one is far shorter). */
    public static final int MAX_CURSOR_LENGTH = 1024;

    private static final String MALFORMED_CURSOR = "Malformed pagination cursor";
    private static final Pattern LONG_NUMBER = Pattern.compile("-?[0-9]{1,19}");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private CursorCodec() {
    }

    /**
     * Encodes the last evaluated key of a query page.
     *
     * @param lastEvaluatedKey the key, may be {@code null} or empty
     * @return the cursor, or {@code null} when there is no further page
     * @throws IllegalArgumentException when a key attribute is neither a string nor a number
     */
    public static String encode(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        String payload = new TreeMap<>(lastEvaluatedKey).entrySet().stream()
                .map(entry -> encodeEntry(entry.getKey(), entry.getValue()))
                .collect(Collectors.joining("&"));
        return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a client cursor into an exclusive start key.
     *
     * @param cursor    the cursor, {@code null} or blank for the first page
     * @param keySchema the key attributes of the queried index with their types
     * @return the start key, empty for the first page
     * @throws DomainValidationException when the cursor is malformed
     */
    public static Map<String, AttributeValue> decode(String cursor, Map<String, ScalarAttributeType> keySchema) {
        if (cursor == null || cursor.isBlank()) {
            return Map.of();
        }
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            throw new DomainValidationException(MALFORMED_CURSOR);
        }
        try {
            String payload = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
            Map<String, AttributeValue> key = new HashMap<>();
            for (String entry : payload.split("&", -1)) {
                decodeEntry(entry, keySchema, key);
            }
            if (!key.keySet().equals(keySchema.keySet())) {
                throw new DomainValidationException(MALFORMED_CURSOR);
            }
            return Map.copyOf(key);
        } catch (IllegalArgumentException malformedEncoding) {
            throw new DomainValidationException(MALFORMED_CURSOR);
        }
    }

    private static String encodeEntry(String name, AttributeValue value) {
        if (value.s() != null) {
            return urlEncode(name) + "=S:" + urlEncode(value.s());
        }
        if (value.n() != null) {
            return urlEncode(name) + "=N:" + urlEncode(value.n());
        }
        throw new IllegalArgumentException("Unsupported key attribute type for " + name);
    }

    private static void decodeEntry(String entry, Map<String, ScalarAttributeType> keySchema,
                                    Map<String, AttributeValue> key) {
        int nameEnd = entry.indexOf('=');
        int typeEnd = entry.indexOf(':', nameEnd + 1);
        if (nameEnd <= 0 || typeEnd != nameEnd + 2) {
            throw new DomainValidationException(MALFORMED_CURSOR);
        }
        String name = URLDecoder.decode(entry.substring(0, nameEnd), StandardCharsets.UTF_8);
        String type = entry.substring(nameEnd + 1, typeEnd);
        String value = URLDecoder.decode(entry.substring(typeEnd + 1), StandardCharsets.UTF_8);
        ScalarAttributeType expectedType = keySchema.get(name);
        if (expectedType == null || !expectedType.toString().equals(type) || key.containsKey(name)) {
            throw new DomainValidationException(MALFORMED_CURSOR);
        }
        if (expectedType == ScalarAttributeType.N) {
            key.put(name, AttributeValue.fromN(Long.toString(parseLong(value))));
        } else {
            key.put(name, AttributeValue.fromS(value));
        }
    }

    /**
     * Parses a numeric key value without ever expanding it: only plain integers of at most 19 digits are accepted,
     * and {@link Long#parseLong} rejects the ones beyond the {@code long} range with a
     * {@link NumberFormatException} (an {@link IllegalArgumentException}, reported as a malformed cursor).
     */
    private static long parseLong(String value) {
        if (!LONG_NUMBER.matcher(value).matches()) {
            throw new DomainValidationException(MALFORMED_CURSOR);
        }
        return Long.parseLong(value);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
