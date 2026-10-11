package com.ticketing.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.ticketing.enums.ErrorCodeEnum;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.enums.TicketStatusEnum;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class TicketingExceptionTest {

    private static final Throwable CAUSE = new IOException("boom");

    static Stream<Arguments> everyConcreteException() {
        return Stream.of(
                Arguments.of(new DomainValidationException("quantity must be positive"),
                        ErrorCodeEnum.VALIDATION_ERROR, false, "quantity must be positive"),
                Arguments.of(new DomainValidationException(ErrorCodeEnum.VALIDATION_ERROR, "bad cursor"),
                        ErrorCodeEnum.VALIDATION_ERROR, false, "bad cursor"),
                Arguments.of(new DomainValidationException(ErrorCodeEnum.INVALID_IDEMPOTENCY_KEY, "bad key"),
                        ErrorCodeEnum.INVALID_IDEMPOTENCY_KEY, false, "bad key"),
                Arguments.of(new EventNotFoundException("e-1"),
                        ErrorCodeEnum.EVENT_NOT_FOUND, false, "Event not found: e-1"),
                Arguments.of(new EventNotOnSaleException("e-1", EventStatusEnum.CLOSED),
                        ErrorCodeEnum.EVENT_NOT_ON_SALE, false, "Event e-1 is not on sale (status CLOSED)"),
                Arguments.of(new OrderNotFoundException("o-1"),
                        ErrorCodeEnum.ORDER_NOT_FOUND, false, "Order not found: o-1"),
                Arguments.of(new InsufficientInventoryException("e-1", 3, 1),
                        ErrorCodeEnum.SOLD_OUT, false, "Not enough tickets for event e-1: requested 3, available 1"),
                Arguments.of(new InvalidOrderStateException("o-1", "order is already completed"),
                        ErrorCodeEnum.INVALID_ORDER_STATE, false,
                        "Order o-1 is in an invalid state: order is already completed"),
                Arguments.of(new InvalidTicketTransitionException(TicketStatusEnum.SOLD, TicketStatusEnum.AVAILABLE),
                        ErrorCodeEnum.INVALID_TICKET_TRANSITION, false, "Invalid ticket transition from SOLD to AVAILABLE"),
                Arguments.of(new ReservationExpiredException("o-1"),
                        ErrorCodeEnum.HOLD_EXPIRED, false, "Reservation hold expired for order o-1"),
                Arguments.of(new IdempotencyKeyConflictException("key-12345"),
                        ErrorCodeEnum.IDEMPOTENCY_KEY_REUSED, false,
                        "Idempotency key key-12345 was already used with a different request"),
                Arguments.of(new RateLimitExceededException(7),
                        ErrorCodeEnum.RATE_LIMIT_EXCEEDED, false, "Rate limit exceeded, retry after 7 s"),
                Arguments.of(new InventoryConflictException("transaction conflict"),
                        ErrorCodeEnum.INVENTORY_CONFLICT, true, "transaction conflict"),
                Arguments.of(new InventoryConflictException("throttled", CAUSE),
                        ErrorCodeEnum.INVENTORY_CONFLICT, true, "throttled"),
                Arguments.of(new MessagePublishingException("queue unavailable", CAUSE),
                        ErrorCodeEnum.MESSAGE_PUBLISHING_FAILED, true, "queue unavailable"),
                Arguments.of(new PaymentGatewayException("circuit open"),
                        ErrorCodeEnum.PAYMENT_GATEWAY_UNAVAILABLE, true, "circuit open"),
                Arguments.of(new PaymentGatewayException("timeout", CAUSE),
                        ErrorCodeEnum.PAYMENT_GATEWAY_UNAVAILABLE, true, "timeout"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("everyConcreteException")
    void shouldExposeCodeRetrySemanticsAndMessage(TicketingException exception, ErrorCodeEnum code,
                                                  boolean retryable, String message) {
        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.errorCode()).isEqualTo(code);
        assertThat(exception.retryable()).isEqualTo(retryable).isEqualTo(code.retryable());
        assertThat(exception.getMessage()).isEqualTo(message);
        assertThat(TicketingException.isRetryable(exception)).isEqualTo(retryable);
    }

    @Test
    void shouldKeepTheTechnicalCause() {
        assertThat(new InventoryConflictException("conflict", CAUSE)).hasCause(CAUSE);
        assertThat(new MessagePublishingException("failed", CAUSE)).hasCause(CAUSE);
        assertThat(new PaymentGatewayException("failed", CAUSE)).hasCause(CAUSE);
        assertThat(new PaymentGatewayException("failed")).hasNoCause();
    }

    @Test
    void shouldExposeTransitionStatuses() {
        InvalidTicketTransitionException exception =
                new InvalidTicketTransitionException(TicketStatusEnum.AVAILABLE, TicketStatusEnum.SOLD);

        assertThat(exception.from()).isEqualTo(TicketStatusEnum.AVAILABLE);
        assertThat(exception.to()).isEqualTo(TicketStatusEnum.SOLD);
    }

    @Test
    void shouldExposeRetryAfterSeconds() {
        assertThat(new RateLimitExceededException(0).retryAfterSeconds()).isZero();
        assertThat(new RateLimitExceededException(30).retryAfterSeconds()).isEqualTo(30);
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitExceededException(-1));
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCodeEnum.class, names = {"VALIDATION_ERROR", "INVALID_IDEMPOTENCY_KEY"},
            mode = EnumSource.Mode.EXCLUDE)
    void shouldRejectNonValidationCodesInValidationException(ErrorCodeEnum code) {
        assertThatIllegalArgumentException().isThrownBy(() -> new DomainValidationException(code, "message"));
    }

    @Test
    void shouldRejectNullValidationCode() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DomainValidationException(null, "message"));
    }

    @Test
    void shouldNotConsiderForeignOrMissingErrorsRetryable() {
        assertThat(TicketingException.isRetryable(new IllegalStateException("boom"))).isFalse();
        assertThat(TicketingException.isRetryable(null)).isFalse();
    }
}
