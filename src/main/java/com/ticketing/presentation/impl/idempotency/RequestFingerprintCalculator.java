package com.ticketing.presentation.impl.idempotency;

import com.ticketing.util.HashUtil;
import java.util.Objects;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

@Component
public class RequestFingerprintCalculator {
    static final String PLACE_ORDER = "place-order";
    static final String ISSUE_COMPLIMENTARY = "issue-complimentary";

    private static final char SEPARATOR = '|';
    private static final char ESCAPE = '\\';

    public String placeOrder(String eventId, int quantity) {
        return HashUtil.sha256Hex(canonical(PLACE_ORDER, eventId, Integer.toString(quantity)));
    }

    public String issueComplimentary(String eventId, String beneficiaryId, int quantity) {
        return HashUtil.sha256Hex(canonical(ISSUE_COMPLIMENTARY, eventId, beneficiaryId, Integer.toString(quantity)));
    }

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
