package com.ticketing.presentation.impl.idempotency;

import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.enums.ErrorCodeEnum;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates the {@code Idempotency-Key} header of the state-changing endpoints. The header is declared optional on the
 * controller contracts so that a missing value reaches this resolver and yields a {@code 400 INVALID_IDEMPOTENCY_KEY}
 * problem instead of a generic binding error.
 */
@Component
public class IdempotencyKeyResolver {

    /** Accepted keys: 8 to 64 letters, digits or dashes (a UUID fits). */
    public static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    /**
     * Validates the header value.
     *
     * @param headerValue raw header value, {@code null} when absent
     * @return the key, unchanged
     * @throws DomainValidationException with {@link ErrorCodeEnum#INVALID_IDEMPOTENCY_KEY} when the header is missing
     *                                   or malformed
     */
    public String resolve(String headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            throw new DomainValidationException(ErrorCodeEnum.INVALID_IDEMPOTENCY_KEY,
                    "The Idempotency-Key header is required");
        }
        if (!KEY_PATTERN.matcher(headerValue).matches()) {
            throw new DomainValidationException(ErrorCodeEnum.INVALID_IDEMPOTENCY_KEY,
                    "The Idempotency-Key header must contain 8 to 64 letters, digits or dashes");
        }
        return headerValue;
    }
}
