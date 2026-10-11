package com.ticketing.presentation.impl.support;

import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.enums.ErrorCodeEnum;
import java.util.regex.Pattern;

/**
 * Validates the resource identifiers taken from request paths ({@code eventId}, {@code orderId}, {@code ticketId})
 * before they reach a use case. Every identifier the service issues fits the pattern (UUIDs, {@code T-000042}), so a
 * value that does not is client input that cannot name a resource: it is rejected with {@code 400 VALIDATION_ERROR}
 * instead of travelling to DynamoDB (whose key attributes are limited to 2048 bytes, an oversized key would surface as
 * a server error).
 *
 * <p>The checks live here rather than as constraints on the controller contracts because the contracts are shared
 * interfaces and Bean Validation forbids adding parameter constraints to an overriding method.
 */
public final class ResourceIdValidator {

    /** Name of the event identifier path variable. */
    public static final String EVENT_ID = "eventId";

    /** Name of the order identifier path variable. */
    public static final String ORDER_ID = "orderId";

    /** Name of the ticket identifier path variable. */
    public static final String TICKET_ID = "ticketId";

    /** Accepted identifiers: 1 to 64 letters, digits or dashes. */
    public static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private ResourceIdValidator() {
    }

    /**
     * Validates one path identifier.
     *
     * @param parameterName name of the path variable, used in the client-facing message (the value is never echoed)
     * @param value         raw value, may be {@code null}
     * @return the value, unchanged
     * @throws DomainValidationException with {@link ErrorCodeEnum#VALIDATION_ERROR} when the value is missing or does
     *                                   not match {@link #ID_PATTERN}
     */
    public static String requireValid(String parameterName, String value) {
        if (value == null || !ID_PATTERN.matcher(value).matches()) {
            throw new DomainValidationException(ErrorCodeEnum.VALIDATION_ERROR,
                    "The path parameter '" + parameterName + "' must contain 1 to 64 letters, digits or dashes");
        }
        return value;
    }
}
