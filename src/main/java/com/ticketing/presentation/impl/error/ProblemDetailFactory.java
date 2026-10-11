package com.ticketing.presentation.impl.error;

import com.ticketing.domain.exception.TicketingException;
import com.ticketing.enums.ErrorCodeEnum;
import com.ticketing.presentation.context.RequestContext;
import java.net.URI;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@RequiredArgsConstructor
@Component
public class ProblemDetailFactory {
    public static final String ERROR_CODE = "errorCode";

    public static final String CORRELATION_ID = "correlationId";

    public static final String TIMESTAMP = "timestamp";

    public static final String ERRORS = "errors";

    private final Clock clock;

    public ProblemDetail forErrorCode(ErrorCodeEnum code, String detail, ServerWebExchange exchange) {
        Objects.requireNonNull(code, "code");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.httpStatus()), detail);
        problem.setTitle(code.name());
        problem.setProperty(ERROR_CODE, code.name());
        return decorate(problem, exchange);
    }

    public ProblemDetail forException(TicketingException exception, ServerWebExchange exchange) {
        Objects.requireNonNull(exception, "exception");
        String message = exception.getMessage();
        return forErrorCode(exception.errorCode(), message == null ? exception.errorCode().name() : message,
                exchange);
    }

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

    public static String correlationIdOf(ServerWebExchange exchange) {
        Object attribute = exchange.getAttribute(RequestContext.CORRELATION_ID_KEY);
        if (attribute instanceof String correlationId && !correlationId.isBlank()) {
            return correlationId;
        }
        return exchange.getRequest().getHeaders().getFirst(RequestContext.CORRELATION_ID_HEADER);
    }

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
