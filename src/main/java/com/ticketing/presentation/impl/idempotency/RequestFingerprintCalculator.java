package com.ticketing.presentation.impl.idempotency;

import com.ticketing.util.HashUtil;
import java.util.Objects;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

/**
 * Computes the fingerprint ({@code requestHash}) of an idempotent request: the SHA-256 of a canonical string
 * {@code "<operation>|<field>|<field>..."}. Reusing an idempotency key with a different fingerprint is rejected by the
 * use cases ({@code 422 IDEMPOTENCY_KEY_REUSED}).
 *
 * <p>The requester is not part of the fingerprint: it is already part of the order id (UUID v5 of requester and
 * key). Separators inside field values are escaped, so two different field lists never share a canonical string.
 */
@Component
public class RequestFingerprintCalculator {

    static final String PLACE_ORDER = "place-order";
    static final String ISSUE_COMPLIMENTARY = "issue-complimentary";

    private static final char SEPARATOR = '|';
    private static final char ESCAPE = '\\';

    /**
     * Fingerprint of a place-order request.
     *
     * @param eventId  the event identifier
     * @param quantity the requested tickets
     * @return 64 lowercase hexadecimal characters
     */
    public String placeOrder(String eventId, int quantity) {
        return HashUtil.sha256Hex(canonical(PLACE_ORDER, eventId, Integer.toString(quantity)));
    }

    /**
     * Fingerprint of a complimentary-issue request.
     *
     * @param eventId       the event identifier
     * @param beneficiaryId the customer receiving the tickets
     * @param quantity      the requested tickets
     * @return 64 lowercase hexadecimal characters
     */
    public String issueComplimentary(String eventId, String beneficiaryId, int quantity) {
        return HashUtil.sha256Hex(canonical(ISSUE_COMPLIMENTARY, eventId, beneficiaryId, Integer.toString(quantity)));
    }

    /**
     * Builds the canonical string of an operation.
     *
     * @param operation the operation name
     * @param fields    the payload fields, in a fixed order
     * @return {@code operation|field1|field2...} with {@code |} and {@code \} escaped inside the fields
     */
    static String canonical(String operation, String... fields) {
        StringJoiner joiner = new StringJoiner(String.valueOf(SEPARATOR));
        joiner.add(escape(Objects.requireNonNull(operation, "operation")));
        for (String field : fields) {
            joiner.add(escape(Objects.requireNonNull(field, "field")));
        }
        return joiner.toString();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == SEPARATOR || character == ESCAPE) {
                escaped.append(ESCAPE);
            }
            escaped.append(character);
        }
        return escaped.toString();
    }
}
