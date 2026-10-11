package com.ticketing.presentation.impl.error;

import com.ticketing.domain.exception.TicketingException;
import com.ticketing.enums.ErrorCodeEnum;
import com.ticketing.presentation.context.RequestContext;
import java.net.URI;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * Builds the RFC 9457 problem details returned by the API, so that the controller advice, the security entry points
 * and the rate limiter render identical bodies: {@code status}, {@code title} (the error code), {@code detail},
 * {@code instance} (request path) and the extension members {@code errorCode}, {@code correlationId} and
 * {@code timestamp}.
 */
@Component
public class ProblemDetailFactory {

    /** Extension member holding the stable {@link ErrorCodeEnum} name. */
    public static final String ERROR_CODE = "errorCode";

    /** Extension member holding the correlation id of the request. */
    public static final String CORRELATION_ID = "correlationId";

    /** Extension member holding the instant the problem was produced. */
    public static final String TIMESTAMP = "timestamp";

    /** Extension member holding the field violations of a validation error. */
    public static final String ERRORS = "errors";

    private final Clock clock;

    /**
     * Creates the factory.
     *
     * @param clock source of the {@code timestamp} member
     */
    public ProblemDetailFactory(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Problem of a business error code.
     *
     * @param code     the error code (gives the HTTP status and the title)
     * @param detail   client-safe description
     * @param exchange the current exchange
     * @return the problem
     */
    public ProblemDetail forErrorCode(ErrorCodeEnum code, String detail, ServerWebExchange exchange) {
        Objects.requireNonNull(code, "code");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.httpStatus()), detail);
        problem.setTitle(code.name());
        problem.setProperty(ERROR_CODE, code.name());
        return decorate(problem, exchange);
    }

    /**
     * Problem of a business exception; its message is client-safe by contract.
     *
     * @param exception the exception
     * @param exchange  the current exchange
     * @return the problem
     */
    public ProblemDetail forException(TicketingException exception, ServerWebExchange exchange) {
        Objects.requireNonNull(exception, "exception");
        String message = exception.getMessage();
        return forErrorCode(exception.errorCode(), message == null ? exception.errorCode().name() : message,
                exchange);
    }

    /**
     * Problem of a protocol-level HTTP error (unknown route, unsupported method or media type, ...). The
     * {@code errorCode} member is present only when the status has an equivalent {@link ErrorCodeEnum}.
     *
     * @param status   the HTTP status
     * @param detail   client-safe description, nullable
     * @param exchange the current exchange
     * @return the problem
     */
    public ProblemDetail forStatus(HttpStatusCode status, String detail, ServerWebExchange exchange) {
        Objects.requireNonNull(status, "status");
        Optional<ErrorCodeEnum> code = errorCodeOf(status);
        if (code.isPresent()) {
            return forErrorCode(code.get(), detail, exchange);
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        HttpStatus knownStatus = HttpStatus.resolve(status.value());
        problem.setTitle(knownStatus == null ? "HTTP " + status.value() : knownStatus.getReasonPhrase());
        return decorate(problem, exchange);
    }

    /**
     * Correlation id of the exchange: the attribute set by the correlation filter, else the request header.
     *
     * @param exchange the current exchange
     * @return the correlation id, {@code null} when unknown
     */
    public static String correlationIdOf(ServerWebExchange exchange) {
        Object attribute = exchange.getAttribute(RequestContext.CORRELATION_ID_KEY);
        if (attribute instanceof String correlationId && !correlationId.isBlank()) {
            return correlationId;
        }
        return exchange.getRequest().getHeaders().getFirst(RequestContext.CORRELATION_ID_HEADER);
    }

    /**
     * Business error code equivalent to a protocol status, if any.
     *
     * @param status the HTTP status
     * @return the error code of the 400, 401, 403, 404, 405, 406, 413, 415, 429 and 500 statuses, empty otherwise
     */
    static Optional<ErrorCodeEnum> errorCodeOf(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> Optional.of(ErrorCodeEnum.VALIDATION_ERROR);
            case 401 -> Optional.of(ErrorCodeEnum.UNAUTHORIZED);
            case 403 -> Optional.of(ErrorCodeEnum.FORBIDDEN);
            case 404 -> Optional.of(ErrorCodeEnum.RESOURCE_NOT_FOUND);
            case 405 -> Optional.of(ErrorCodeEnum.METHOD_NOT_ALLOWED);
            case 406 -> Optional.of(ErrorCodeEnum.NOT_ACCEPTABLE);
            case 413 -> Optional.of(ErrorCodeEnum.PAYLOAD_TOO_LARGE);
            case 415 -> Optional.of(ErrorCodeEnum.UNSUPPORTED_MEDIA_TYPE);
            case 429 -> Optional.of(ErrorCodeEnum.RATE_LIMIT_EXCEEDED);
            case 500 -> Optional.of(ErrorCodeEnum.INTERNAL_ERROR);
            default -> Optional.empty();
        };
    }

    private ProblemDetail decorate(ProblemDetail problem, ServerWebExchange exchange) {
        Objects.requireNonNull(exchange, "exchange");
        problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
        String correlationId = correlationIdOf(exchange);
        if (correlationId != null) {
            problem.setProperty(CORRELATION_ID, correlationId);
        }
        problem.setProperty(TIMESTAMP, clock.instant());
        return problem;
    }
}
