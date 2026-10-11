package com.ticketing.presentation.impl.idempotency;

import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.enums.ErrorCodeEnum;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyKeyResolver {
    public static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

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
