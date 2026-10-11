package com.ticketing.presentation.impl.support;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

@RequiredArgsConstructor
@Component
public class JsonMessageReader {
    private final JsonMapper jsonMapper;
    private final Validator validator;

    public <T> Mono<T> read(String payload, Class<T> type) {
        Objects.requireNonNull(type, "type");
        return Mono.fromCallable(() -> validate(parse(payload, type), type));
    }

    private <T> T parse(String payload, Class<T> type) {
        if (payload == null || payload.isBlank()) {
            throw new MalformedMessageException(type.getSimpleName() + " is empty", null);
        }
        ObjectReader reader = jsonMapper.readerFor(type).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try {
            T message = reader.readValue(payload);
            if (message == null) {
                throw new MalformedMessageException(type.getSimpleName() + " is null", null);
            }
            return message;
        } catch (JacksonException e) {
            throw new MalformedMessageException(type.getSimpleName() + " is not valid JSON for its schema", e);
        }
    }

    private <T> T validate(T message, Class<T> type) {
        Set<ConstraintViolation<T>> violations = validator.validate(message);
        if (!violations.isEmpty()) {
            String invalidFields = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new MalformedMessageException(type.getSimpleName() + " violates its schema: " + invalidFields, null);
        }
        return message;
    }

    public static final class MalformedMessageException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public MalformedMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
