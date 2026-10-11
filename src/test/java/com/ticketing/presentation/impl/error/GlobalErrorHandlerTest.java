package com.ticketing.presentation.impl.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.domain.exception.EventNotFoundException;
import com.ticketing.domain.exception.EventNotOnSaleException;
import com.ticketing.domain.exception.IdempotencyKeyConflictException;
import com.ticketing.domain.exception.InsufficientInventoryException;
import com.ticketing.domain.exception.InvalidOrderStateException;
import com.ticketing.domain.exception.InvalidTicketTransitionException;
import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.exception.MessagePublishingException;
import com.ticketing.domain.exception.OrderNotFoundException;
import com.ticketing.domain.exception.PaymentGatewayException;
import com.ticketing.domain.exception.RateLimitExceededException;
import com.ticketing.domain.exception.ReservationExpiredException;
import com.ticketing.domain.exception.TicketingException;
import com.ticketing.enums.ErrorCodeEnum;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.presentation.context.RequestContext;
import com.ticketing.presentation.impl.error.GlobalErrorHandler.FieldViolation;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

class GlobalErrorHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

    private GlobalErrorHandler handler;
    private MockServerWebExchange exchange;

    /** A business exception carrying the internal error code (its message must never reach the client). */
    private static final class InternalTestException extends TicketingException {
        InternalTestException() {
            super(ErrorCodeEnum.INTERNAL_ERROR, "jdbc password=secret");
        }
    }

    @BeforeEach
    void setUp() {
        handler = new GlobalErrorHandler(new ProblemDetailFactory(Clock.fixed(NOW, ZoneOffset.UTC)));
        exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/events/e-1/orders"));
        exchange.getAttributes().put(RequestContext.CORRELATION_ID_KEY, "corr-1");
    }

    @SuppressWarnings("unused") // target of the method parameters used to build binding exceptions
    private void sample(String quantity) {
    }

    private static MethodParameter parameter() throws NoSuchMethodException {
        Method method = GlobalErrorHandlerTest.class.getDeclaredMethod("sample", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
        return parameter;
    }

    private static void assertProblem(ResponseEntity<ProblemDetail> response, int status, String errorCode) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getProperties())
                .containsEntry(ProblemDetailFactory.CORRELATION_ID, "corr-1")
                .containsEntry(ProblemDetailFactory.TIMESTAMP, NOW);
        if (errorCode == null) {
            assertThat(problem.getProperties()).doesNotContainKey(ProblemDetailFactory.ERROR_CODE);
        } else {
            assertThat(problem.getProperties()).containsEntry(ProblemDetailFactory.ERROR_CODE, errorCode);
            assertThat(problem.getTitle()).isEqualTo(errorCode);
        }
    }

    static Stream<Arguments> businessErrors() {
        return Stream.of(
                Arguments.of(new DomainValidationException("bad"), 400, "VALIDATION_ERROR"),
                Arguments.of(new DomainValidationException(ErrorCodeEnum.INVALID_IDEMPOTENCY_KEY, "bad key"), 400,
                        "INVALID_IDEMPOTENCY_KEY"),
                Arguments.of(new EventNotFoundException("e-1"), 404, "EVENT_NOT_FOUND"),
                Arguments.of(new OrderNotFoundException("o-1"), 404, "ORDER_NOT_FOUND"),
                Arguments.of(new EventNotOnSaleException("e-1", EventStatusEnum.CLOSED), 409, "EVENT_NOT_ON_SALE"),
                Arguments.of(new InsufficientInventoryException("e-1", 2, 0), 409, "SOLD_OUT"),
                Arguments.of(new InvalidOrderStateException("o-1", "cancelled"), 409, "INVALID_ORDER_STATE"),
                Arguments.of(new InvalidTicketTransitionException(TicketStatusEnum.SOLD, TicketStatusEnum.RESERVED),
                        409, "INVALID_TICKET_TRANSITION"),
                Arguments.of(new ReservationExpiredException("o-1"), 410, "HOLD_EXPIRED"),
                Arguments.of(new IdempotencyKeyConflictException("key-12345"), 422, "IDEMPOTENCY_KEY_REUSED"));
    }

    @ParameterizedTest
    @MethodSource("businessErrors")
    void shouldMapBusinessErrorsToTheirStatusAndMessage(TicketingException error, int status, String errorCode) {
        ResponseEntity<ProblemDetail> response = handler.handleTicketingException(error, exchange);

        assertProblem(response, status, errorCode);
        assertThat(response.getBody().getDetail()).isEqualTo(error.getMessage());
        assertThat(response.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
    }

    @Test
    void shouldAnnounceRetryAfterForRateLimiting() {
        ResponseEntity<ProblemDetail> response =
                handler.handleTicketingException(new RateLimitExceededException(7), exchange);

        assertProblem(response, 429, "RATE_LIMIT_EXCEEDED");
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("7");
    }

    @Test
    void shouldAnnounceRetryAfterForTransientFailures() {
        for (TicketingException transientError : List.of(new InventoryConflictException("conflict"),
                new MessagePublishingException("queue down", new IllegalStateException("sdk")),
                new PaymentGatewayException("circuit open"))) {
            ResponseEntity<ProblemDetail> response = handler.handleTicketingException(transientError, exchange);

            assertProblem(response, 503, transientError.errorCode().name());
            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        }
    }

    @Test
    void shouldHideTheMessageOfInternalBusinessErrors() {
        ResponseEntity<ProblemDetail> response = handler.handleTicketingException(new InternalTestException(),
                exchange);

        assertProblem(response, 500, "INTERNAL_ERROR");
        assertThat(response.getBody().getDetail()).isEqualTo(GlobalErrorHandler.INTERNAL_ERROR_DETAIL);
    }

    @Test
    void shouldListFieldViolationsSortedByFieldAndMessage() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "createEventRequestDto");
        bindingResult.addError(new FieldError("createEventRequestDto", "totalCapacity", "must be >= 1"));
        bindingResult.addError(new FieldError("createEventRequestDto", "currency", "must match ^[A-Z]{3}$"));
        bindingResult.addError(new FieldError("createEventRequestDto", "currency", null, false, null, null, null));
        bindingResult.addError(new ObjectError("createEventRequestDto", "dates are inconsistent"));

        ResponseEntity<ProblemDetail> response = handler.handleBindException(
                new WebExchangeBindException(parameter(), bindingResult), exchange);

        assertProblem(response, 400, "VALIDATION_ERROR");
        assertThat(response.getBody().getDetail()).isEqualTo(GlobalErrorHandler.VALIDATION_DETAIL);
        assertThat(response.getBody().getProperties().get(ProblemDetailFactory.ERRORS))
                .asInstanceOf(InstanceOfAssertFactories.list(FieldViolation.class))
                .containsExactly(
                        new FieldViolation("createEventRequestDto", "dates are inconsistent"),
                        new FieldViolation("currency", null),
                        new FieldViolation("currency", "must match ^[A-Z]{3}$"),
                        new FieldViolation("totalCapacity", "must be >= 1"));
    }

    @Test
    void shouldNameTheParameterOfTypeMismatches() throws NoSuchMethodException {
        ServerWebInputException error = new ServerWebInputException("Type mismatch.", parameter(),
                new TypeMismatchException("x", Integer.class));

        ResponseEntity<ProblemDetail> response = handler.handleInputException(error, exchange);

        assertProblem(response, 400, "VALIDATION_ERROR");
        assertThat(response.getBody().getDetail()).isEqualTo("Invalid value for parameter 'quantity'");
    }

    @Test
    void shouldFallBackToTheFrameworkDetailOfInputErrors() throws NoSuchMethodException {
        assertThat(GlobalErrorHandler.inputErrorDetail(new ServerWebInputException("Type mismatch.",
                mock(MethodParameter.class), new TypeMismatchException("x", Integer.class))))
                .isEqualTo("Type mismatch.");
        assertThat(GlobalErrorHandler.inputErrorDetail(new ServerWebInputException("Type mismatch.", null,
                new TypeMismatchException("x", Integer.class)))).isEqualTo("Type mismatch.");
        assertThat(GlobalErrorHandler.inputErrorDetail(new ServerWebInputException("No request body")))
                .isEqualTo("No request body");
        assertThat(GlobalErrorHandler.inputErrorDetail(new ServerWebInputException("Failed to read HTTP message",
                null, new DecodingException("JSON decoding error: unexpected EOF"))))
                .isEqualTo(GlobalErrorHandler.MALFORMED_BODY_DETAIL);
    }

    @Test
    void shouldUseAGenericDetailWhenTheFrameworkHasNone() {
        ServerWebInputException error = mock(ServerWebInputException.class);
        given(error.getBody()).willReturn(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST));
        given(error.getHeaders()).willReturn(new HttpHeaders());

        ResponseEntity<ProblemDetail> response = handler.handleInputException(error, exchange);

        assertProblem(response, 400, "VALIDATION_ERROR");
        assertThat(response.getBody().getDetail()).isEqualTo(GlobalErrorHandler.VALIDATION_DETAIL);
    }

    @Test
    void shouldRejectOversizedBodies() {
        ResponseEntity<ProblemDetail> response = handler.handleBodyTooLarge(
                new DataBufferLimitException("Exceeded limit on max bytes to buffer : 262144"), exchange);

        assertProblem(response, 413, null);
        assertThat(response.getBody().getDetail()).isEqualTo(GlobalErrorHandler.BODY_TOO_LARGE_DETAIL);
    }

    @Test
    void shouldKeepTheStatusAndHeadersOfFrameworkErrors() {
        ResponseEntity<ProblemDetail> notFound = handler.handleErrorResponse(
                new ResponseStatusException(HttpStatus.NOT_FOUND), exchange);
        ResponseEntity<ProblemDetail> notAllowed = handler.handleErrorResponse(
                new MethodNotAllowedException(HttpMethod.PUT, Set.of(HttpMethod.GET)), exchange);

        assertProblem(notFound, 404, null);
        assertThat(notFound.getBody().getTitle()).isEqualTo("Not Found");
        assertProblem(notAllowed, 405, null);
        assertThat(notAllowed.getHeaders().getFirst(HttpHeaders.ALLOW)).isEqualTo("GET");
        assertThat(notAllowed.getBody().getDetail()).contains("GET");
    }

    @Test
    void shouldHideTheDetailOfFrameworkServerErrors() {
        ResponseEntity<ProblemDetail> unavailable = handler.handleErrorResponse(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "pool exhausted on db-1"), exchange);
        ResponseEntity<ProblemDetail> internal = handler.handleErrorResponse(
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "NPE in handler"), exchange);

        assertProblem(unavailable, 503, null);
        assertThat(unavailable.getBody().getDetail()).isEqualTo(GlobalErrorHandler.INTERNAL_ERROR_DETAIL);
        assertProblem(internal, 500, "INTERNAL_ERROR");
        assertThat(internal.getBody().getDetail()).isEqualTo(GlobalErrorHandler.INTERNAL_ERROR_DETAIL);
    }

    @Test
    void shouldMapSecurityExceptions() {
        ResponseEntity<ProblemDetail> unauthorized = handler.handleAuthenticationException(
                new AuthenticationCredentialsNotFoundException("no token"), exchange);
        ResponseEntity<ProblemDetail> forbidden = handler.handleAccessDeniedException(
                new AccessDeniedException("denied"), exchange);

        assertProblem(unauthorized, 401, "UNAUTHORIZED");
        assertThat(unauthorized.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertProblem(forbidden, 403, "FORBIDDEN");
    }

    @Test
    void shouldHideUnexpectedErrors() {
        ResponseEntity<ProblemDetail> response = handler.handleUnexpectedException(
                new IllegalStateException("connection refused: ticketing-orders"), exchange);

        assertProblem(response, 500, "INTERNAL_ERROR");
        assertThat(response.getBody().getDetail()).isEqualTo(GlobalErrorHandler.INTERNAL_ERROR_DETAIL);
        assertThat(response.getBody().toString()).doesNotContain("ticketing-orders");
    }

    @Test
    void shouldRequireTheProblemFactory() {
        assertThatNullPointerException().isThrownBy(() -> new GlobalErrorHandler(null));
    }
}
