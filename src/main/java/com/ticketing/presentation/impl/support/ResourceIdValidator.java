package com.ticketing.presentation.impl.support;

import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.enums.ErrorCodeEnum;
import java.util.regex.Pattern;

public final class ResourceIdValidator {
    public static final String EVENT_ID = "eventId";

    public static final String ORDER_ID = "orderId";

    public static final String TICKET_ID = "ticketId";

    public static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private ResourceIdValidator() {
    }

    public static String requireValid(String parameterName, String value) {
        if (value == null || !ID_PATTERN.matcher(value).matches()) {
            throw new DomainValidationException(ErrorCodeEnum.VALIDATION_ERROR,
                    "The path parameter '" + parameterName + "' must contain 1 to 64 letters, digits or dashes");
        }
        return value;
    }
}
