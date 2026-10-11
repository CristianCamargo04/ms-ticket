package com.ticketing.presentation.impl.error;

import com.ticketing.domain.exception.RateLimitExceededException;
import com.ticketing.domain.exception.TicketingException;
import com.ticketing.enums.ErrorCodeEnum;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

@RequiredArgsConstructor
@Slf4j
@RestControllerAdvice
public class GlobalErrorHandler {
    static final String INTERNAL_ERROR_DETAIL = "An unexpected error occurred";

    static final String TRANSIENT_RETRY_AFTER_SECONDS = "1";

    static final String VALIDATION_DETAIL = "The request is invalid";
    static final String MALFORMED_BODY_DETAIL = "The request body is malformed";
    static final String BODY_TOO_LARGE_DETAIL = "The request body exceeds the maximum allowed size";
    static final String UNAUTHORIZED_DETAIL = "Authentication is required to access this resource";
    static final String FORBIDDEN_DETAIL = "Access to this resource is denied";
    static final String BEARER_CHALLENGE = "Bearer";

    private static final int NOT_FOUND = 404;

    static final String NOT_FOUND_DETAIL = "No resource matches the requested path.";

    private final ProblemDetailFactory problems;

    @ExceptionHandler(TicketingException.class)
    public ResponseEntity<ProblemDetail> handleTicketingException(TicketingException exception,
                                                                  ServerWebExchange exchange) {
        ErrorCodeEnum code = exception.errorCode();
        HttpHeaders headers = new HttpHeaders();
        if (exception instanceof RateLimitExceededException rateLimit) {
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(rateLimit.retryAfterSeconds()));
        } else if (exception.retryable()) {
            headers.set(HttpHeaders.RETRY_AFTER, TRANSIENT_RETRY_AFTER_SECONDS);
        }
        ProblemDetail problem;
        if (code == ErrorCodeEnum.INTERNAL_ERROR) {
            log.error("Internal error on {}", describe(exchange), exception);
            problem = problems.forErrorCode(code, INTERNAL_ERROR_DETAIL, exchange);
        } else {
            if (exception.retryable()) {
                log.warn("Transient failure {} on {}: {}", code, describe(exchange), exception.getMessage());
            } else {
                log.debug("Request rejected with {} on {}: {}", code, describe(exchange), exception.getMessage());
            }
            problem = problems.forException(exception, exchange);
        }
        return respond(problem, headers);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ProblemDetail> handleBindException(WebExchangeBindException exception,
                                                             ServerWebExchange exchange) {
        List<FieldViolation> violations = Stream.concat(
                        exception.getFieldErrors().stream()
                                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage())),
                        exception.getGlobalErrors().stream()
                                .map(error -> new FieldViolation(error.getObjectName(), error.getDefaultMessage())))
                .sorted(FieldViolation.ORDER)
                .toList();
        log.debug("Validation failed on {}: {}", describe(exchange), violations);
        ProblemDetail problem = problems.forErrorCode(ErrorCodeEnum.VALIDATION_ERROR, VALIDATION_DETAIL, exchange);
        problem.setProperty(ProblemDetailFactory.ERRORS, violations);
        return respond(problem, new HttpHeaders());
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ProblemDetail> handleInputException(ServerWebInputException exception,
                                                              ServerWebExchange exchange) {
        log.debug("Invalid input on {}: {}", describe(exchange), exception.getReason());
        return respond(problems.forErrorCode(ErrorCodeEnum.VALIDATION_ERROR, inputErrorDetail(exception), exchange),
                exception.getHeaders());
    }

    @ExceptionHandler(DataBufferLimitException.class)
    public ResponseEntity<ProblemDetail> handleBodyTooLarge(DataBufferLimitException exception,
                                                            ServerWebExchange exchange) {
        log.debug("Request body too large on {}: {}", describe(exchange), exception.getMessage());
        return respond(problems.forStatus(HttpStatus.CONTENT_TOO_LARGE, BODY_TOO_LARGE_DETAIL, exchange),
                new HttpHeaders());
    }

    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ProblemDetail> handleErrorResponse(ErrorResponseException exception,
                                                             ServerWebExchange exchange) {
        HttpStatusCode status = exception.getStatusCode();
        String detail;
        if (status.is5xxServerError()) {
            log.error("Server error {} on {}", status.value(), describe(exchange), exception);
            detail = INTERNAL_ERROR_DETAIL;
        } else {
            log.debug("Request rejected with {} on {}: {}", status.value(), describe(exchange),
                    exception.getMessage());
            detail = status.value() == NOT_FOUND ? NOT_FOUND_DETAIL : exception.getBody().getDetail();
        }
        return respond(problems.forStatus(status, detail, exchange), exception.getHeaders());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationException(AuthenticationException exception,
                                                                       ServerWebExchange exchange) {
        log.debug("Unauthenticated request on {}: {}", describe(exchange), exception.getMessage());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE);
        return respond(problems.forErrorCode(ErrorCodeEnum.UNAUTHORIZED, UNAUTHORIZED_DETAIL, exchange), headers);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDeniedException(AccessDeniedException exception,
                                                                     ServerWebExchange exchange) {
        log.debug("Access denied on {}: {}", describe(exchange), exception.getMessage());
        return respond(problems.forErrorCode(ErrorCodeEnum.FORBIDDEN, FORBIDDEN_DETAIL, exchange),
                new HttpHeaders());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(Exception exception, ServerWebExchange exchange) {
        log.error("Unexpected error on {}", describe(exchange), exception);
        return respond(problems.forErrorCode(ErrorCodeEnum.INTERNAL_ERROR, INTERNAL_ERROR_DETAIL, exchange),
                new HttpHeaders());
    }

    static String inputErrorDetail(ServerWebInputException exception) {
        Throwable cause = exception.getCause();
        MethodParameter parameter = exception.getMethodParameter();
        if (cause instanceof TypeMismatchException && parameter != null && parameter.getParameterName() != null) {
            return "Invalid value for parameter '" + parameter.getParameterName() + "'";
        }
        if (cause instanceof DecodingException) {
            return MALFORMED_BODY_DETAIL;
        }
        String detail = exception.getBody().getDetail();
        return detail == null ? VALIDATION_DETAIL : detail;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem, HttpHeaders headers) {
        return ResponseEntity.status(problem.getStatus())
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static String describe(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() + " " + exchange.getRequest().getPath().value()
                + " [correlationId=" + ProblemDetailFactory.correlationIdOf(exchange) + "]";
    }

    public record FieldViolation(String field, String message) {
        static final Comparator<FieldViolation> ORDER = Comparator.comparing(FieldViolation::field)
                .thenComparing(FieldViolation::message, Comparator.nullsFirst(Comparator.naturalOrder()));
    }
}
