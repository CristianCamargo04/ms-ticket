package com.ticketing.presentation.impl.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.domain.exception.EventNotFoundException;
import com.ticketing.domain.exception.TicketingException;
import com.ticketing.enums.ErrorCodeEnum;
import com.ticketing.presentation.context.RequestContext;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class ProblemDetailFactoryTest {
    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

    private final ProblemDetailFactory factory = new ProblemDetailFactory(Clock.fixed(NOW, ZoneOffset.UTC));

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/events/e-1"));
    }

    private static final class SilentException extends TicketingException {
        SilentException() {
            super(ErrorCodeEnum.SOLD_OUT, null);
        }
    }

    @Test
    void shouldBuildTheProblemOfAnErrorCode() {
        MockServerWebExchange exchange = exchange();
        exchange.getAttributes().put(RequestContext.CORRELATION_ID_KEY, "corr-1");

        ProblemDetail problem = factory.forErrorCode(ErrorCodeEnum.HOLD_EXPIRED, "The hold expired", exchange);

        assertThat(problem.getStatus()).isEqualTo(410);
        assertThat(problem.getTitle()).isEqualTo("HOLD_EXPIRED");
        assertThat(problem.getDetail()).isEqualTo("The hold expired");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/api/v1/events/e-1"));
        assertThat(problem.getProperties())
                .containsEntry(ProblemDetailFactory.ERROR_CODE, "HOLD_EXPIRED")
                .containsEntry(ProblemDetailFactory.CORRELATION_ID, "corr-1")
                .containsEntry(ProblemDetailFactory.TIMESTAMP, NOW);
    }

    @Test
    void shouldUseTheMessageOfBusinessExceptions() {
        ProblemDetail problem = factory.forException(new EventNotFoundException("e-1"), exchange());

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getDetail()).isEqualTo(new EventNotFoundException("e-1").getMessage());
        assertThat(factory.forException(new SilentException(), exchange()).getDetail()).isEqualTo("SOLD_OUT");
    }

    @ParameterizedTest
    @CsvSource({"400,VALIDATION_ERROR", "401,UNAUTHORIZED", "403,FORBIDDEN", "429,RATE_LIMIT_EXCEEDED",
        "500,INTERNAL_ERROR"})
    void shouldMapProtocolStatusesToTheirErrorCode(int status, ErrorCodeEnum code) {
        ProblemDetail problem = factory.forStatus(HttpStatusCode.valueOf(status), "detail", exchange());

        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getTitle()).isEqualTo(code.name());
        assertThat(problem.getProperties()).containsEntry(ProblemDetailFactory.ERROR_CODE, code.name());
    }

    @Test
    void shouldKeepProtocolStatusesWithoutBusinessCode() {
        ProblemDetail notFound = factory.forStatus(HttpStatus.NOT_FOUND, null, exchange());
        ProblemDetail unavailable = factory.forStatus(HttpStatus.SERVICE_UNAVAILABLE, "down", exchange());
        ProblemDetail custom = factory.forStatus(HttpStatusCode.valueOf(599), "custom", exchange());

        assertThat(notFound.getStatus()).isEqualTo(404);
        assertThat(notFound.getTitle()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(notFound.getProperties()).containsEntry(ProblemDetailFactory.ERROR_CODE, "RESOURCE_NOT_FOUND")
                .containsKey(ProblemDetailFactory.TIMESTAMP);
        assertThat(unavailable.getProperties()).doesNotContainKey(ProblemDetailFactory.ERROR_CODE);
        assertThat(unavailable.getStatus()).isEqualTo(503);
        assertThat(unavailable.getTitle()).isEqualTo("Service Unavailable");
        assertThat(custom.getTitle()).isEqualTo("HTTP 599");
    }

    @Test
    void shouldResolveTheCorrelationIdFromTheAttributeThenTheHeader() {
        MockServerWebExchange withHeader = MockServerWebExchange.from(MockServerHttpRequest.get("/")
                .header(RequestContext.CORRELATION_ID_HEADER, "from-header"));
        MockServerWebExchange withBlankAttribute = MockServerWebExchange.from(MockServerHttpRequest.get("/")
                .header(RequestContext.CORRELATION_ID_HEADER, "from-header"));
        withBlankAttribute.getAttributes().put(RequestContext.CORRELATION_ID_KEY, " ");
        MockServerWebExchange withForeignAttribute = exchange();
        withForeignAttribute.getAttributes().put(RequestContext.CORRELATION_ID_KEY, 42);

        assertThat(ProblemDetailFactory.correlationIdOf(withHeader)).isEqualTo("from-header");
        assertThat(ProblemDetailFactory.correlationIdOf(withBlankAttribute)).isEqualTo("from-header");
        assertThat(ProblemDetailFactory.correlationIdOf(withForeignAttribute)).isNull();
        assertThat(factory.forErrorCode(ErrorCodeEnum.SOLD_OUT, "x", exchange()).getProperties())
                .doesNotContainKey(ProblemDetailFactory.CORRELATION_ID);
    }

    @Test
    void shouldRejectMissingArguments() {
        assertThatNullPointerException().isThrownBy(() -> factory.forErrorCode(null, "x", exchange()));
        assertThatNullPointerException().isThrownBy(() -> factory.forException(null, exchange()));
        assertThatNullPointerException().isThrownBy(() -> factory.forStatus(null, "x", exchange()));
        assertThatNullPointerException().isThrownBy(() -> factory.forErrorCode(ErrorCodeEnum.SOLD_OUT, "x", null));
    }
}
