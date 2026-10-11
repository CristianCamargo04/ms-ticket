package com.ticketing.presentation.impl.support;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and validates the JSON body of an inbound queue message. Unknown properties are ignored (tolerant reader:
 * producers may add fields without breaking consumers); syntax errors and constraint violations are reported as
 * {@link MalformedMessageException} without echoing the payload (it may carry payment tokens).
 */
@Component
public class JsonMessageReader {

    private final JsonMapper jsonMapper;
    private final Validator validator;

    /**
     * Creates the reader.
     *
     * @param jsonMapper the application JSON mapper
     * @param validator  the Jakarta validator applied to the parsed message
     */
    public JsonMessageReader(JsonMapper jsonMapper, Validator validator) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /**
     * Parses and validates a message body.
     *
     * @param payload the raw JSON body
     * @param type    the message DTO type
     * @param <T>     the message DTO type
     * @return the valid message; error signal {@link MalformedMessageException} when the body is not valid JSON, does
     *         not match the type or violates its constraints
     */
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

    /**
     * An inbound message that can never be processed (poison message): it is not acknowledged, so the queue
     * redelivers it until it is moved to the dead-letter queue.
     */
    public static final class MalformedMessageException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param message description without payload content
         * @param cause   the parsing failure, nullable
         */
        public MalformedMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
