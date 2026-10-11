package com.ticketing.config.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.config.web.ProblemDetailResponseWriter;
import com.ticketing.presentation.context.impl.RequestContextResolverImpl;
import com.ticketing.presentation.impl.error.ProblemDetailFactory;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

class RateLimitWebFilterTest {

    private static final String ORDERS = "/api/v1/events/e-1/orders";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-10T10:00:00Z"));
    private final ProblemDetailFactory problems = new ProblemDetailFactory(clock);
    private final ProblemDetailResponseWriter writer = new ProblemDetailResponseWriter(JsonMapper.builder().build());
    private final CountingChain chain = new CountingChain();

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /** Counts the requests that reached the rest of the chain. */
    private static final class CountingChain implements WebFilterChain {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            return Mono.fromRunnable(calls::incrementAndGet);
        }
    }

    private RateLimitWebFilter filter(long capacity, long refillTokens, Duration refillPeriod) {
        return new RateLimitWebFilter(new TicketingProperties.RateLimit(true, capacity, refillTokens, refillPeriod),
                clock, problems, writer);
    }

    private static ServerWebExchange exchange(HttpMethod method, String path, String subject, String clientIp) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.method(method, path);
        if (clientIp != null) {
            request.header(RequestContextResolverImpl.FORWARDED_FOR_HEADER, clientIp);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        if (subject == null) {
            return exchange;
        }
        Principal principal = () -> subject;
        return exchange.mutate().principal(Mono.just(principal)).build();
    }

    private static ServerWebExchange order(String subject) {
        return exchange(HttpMethod.POST, ORDERS, subject, null);
    }

    private static MockServerHttpResponse response(ServerWebExchange exchange) {
        return (MockServerHttpResponse) exchange.getResponse();
    }

    @Test
    void shouldIgnoreReadsAndNonApiCalls() {
        RateLimitWebFilter filter = filter(1, 1, Duration.ofMinutes(1));

        for (ServerWebExchange exchange : new ServerWebExchange[] {
            exchange(HttpMethod.GET, "/api/v1/events", "c-1", null),
            exchange(HttpMethod.PUT, "/api/v1/events/e-1", "c-1", null),
            exchange(HttpMethod.POST, "/actuator/refresh", "c-1", null),
            exchange(HttpMethod.GET, "/api/v1/events", "c-1", null)}) {
            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
            assertThat(exchange.getResponse().getHeaders().containsHeader(RateLimitWebFilter.REMAINING_HEADER))
                    .isFalse();
        }
        assertThat(chain.calls).hasValue(4);
        assertThat(filter.trackedCallers()).isZero();
    }

    @Test
    void shouldRejectCallersThatExhaustTheirBucket() {
        RateLimitWebFilter filter = filter(2, 1, Duration.ofMinutes(1));
        ServerWebExchange first = order("c-1");
        ServerWebExchange second = order("c-1");
        ServerWebExchange third = order("c-1");

        StepVerifier.create(filter.filter(first, chain)).verifyComplete();
        StepVerifier.create(filter.filter(second, chain)).verifyComplete();
        StepVerifier.create(filter.filter(third, chain)).verifyComplete();

        assertThat(chain.calls).hasValue(2);
        assertThat(first.getResponse().getHeaders().getFirst(RateLimitWebFilter.REMAINING_HEADER)).isEqualTo("1");
        assertThat(second.getResponse().getHeaders().getFirst(RateLimitWebFilter.REMAINING_HEADER)).isEqualTo("0");
        assertThat(third.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(third.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(third.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        StepVerifier.create(response(third).getBodyAsString())
                .assertNext(body -> assertThat(body)
                        .contains("\"errorCode\":\"RATE_LIMIT_EXCEEDED\"")
                        .contains("\"status\":429"))
                .verifyComplete();
    }

    @Test
    void shouldLimitDeletesToo() {
        RateLimitWebFilter filter = filter(1, 1, Duration.ofSeconds(1));
        ServerWebExchange rejected = exchange(HttpMethod.DELETE, "/api/v1/orders/o-1/reservation", "c-1", null);

        StepVerifier.create(filter.filter(exchange(HttpMethod.DELETE, "/api/v1/orders/o-1/reservation", "c-1",
                null), chain)).verifyComplete();
        StepVerifier.create(filter.filter(rejected, chain)).verifyComplete();

        assertThat(chain.calls).hasValue(1);
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    void shouldKeepOneBucketPerSubject() {
        RateLimitWebFilter filter = filter(1, 1, Duration.ofMinutes(1));

        StepVerifier.create(filter.filter(order("c-1"), chain)).verifyComplete();
        StepVerifier.create(filter.filter(order("c-2"), chain)).verifyComplete();
        StepVerifier.create(filter.filter(order("c-1"), chain)).verifyComplete();

        assertThat(chain.calls).hasValue(2);
        assertThat(filter.trackedCallers()).isEqualTo(2);
    }

    @Test
    void shouldFallBackToTheClientIpForAnonymousOrNamelessCallers() {
        RateLimitWebFilter filter = filter(1, 1, Duration.ofMinutes(1));

        StepVerifier.create(filter.filter(exchange(HttpMethod.POST, ORDERS, null, "203.0.113.7"), chain))
                .verifyComplete();
        StepVerifier.create(filter.filter(exchange(HttpMethod.POST, ORDERS, " ", "203.0.113.7"), chain))
                .verifyComplete();
        StepVerifier.create(filter.filter(exchange(HttpMethod.POST, ORDERS, null, "203.0.113.8"), chain))
                .verifyComplete();

        assertThat(chain.calls).hasValue(2);
        assertThat(filter.trackedCallers()).isEqualTo(2);
    }

    @Test
    void shouldShareOneBucketForUnidentifiableClients() {
        RateLimitWebFilter filter = filter(1, 1, Duration.ofMinutes(1));
        ServerWebExchange rejected = exchange(HttpMethod.POST, ORDERS, null, null);

        StepVerifier.create(filter.filter(exchange(HttpMethod.POST, ORDERS, null, null), chain)).verifyComplete();
        StepVerifier.create(filter.filter(rejected, chain)).verifyComplete();

        assertThat(chain.calls).hasValue(1);
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void shouldRefillTokensOverTime() {
        RateLimitWebFilter filter = filter(1, 1, Duration.ofSeconds(10));

        StepVerifier.create(filter.filter(order("c-1"), chain)).verifyComplete();
        StepVerifier.create(filter.filter(order("c-1"), chain)).verifyComplete();
        clock.advance(Duration.ofSeconds(10));
        StepVerifier.create(filter.filter(order("c-1"), chain)).verifyComplete();

        assertThat(chain.calls).hasValue(2);
    }

    @Test
    void shouldRoundRetryAfterUpToWholeSeconds() {
        assertThat(RateLimitWebFilter.retryAfterSeconds(0)).isEqualTo(1);
        assertThat(RateLimitWebFilter.retryAfterSeconds(1)).isEqualTo(1);
        assertThat(RateLimitWebFilter.retryAfterSeconds(TimeUnit.SECONDS.toNanos(1))).isEqualTo(1);
        assertThat(RateLimitWebFilter.retryAfterSeconds(TimeUnit.MILLISECONDS.toNanos(1500))).isEqualTo(2);
        assertThat(RateLimitWebFilter.retryAfterSeconds(TimeUnit.SECONDS.toNanos(60))).isEqualTo(60);
    }

    @Test
    void shouldExpireIdleBucketsOnceTheyWouldBeFull() {
        assertThat(RateLimitWebFilter.timeToFullRefill(
                new TicketingProperties.RateLimit(true, 20, 20, Duration.ofSeconds(1))))
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(RateLimitWebFilter.timeToFullRefill(
                new TicketingProperties.RateLimit(true, 20, 7, Duration.ofSeconds(1))))
                .isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void shouldValidateTheSettings() {
        assertThatIllegalArgumentException().isThrownBy(() -> filter(0, 1, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> filter(1, 0, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> filter(1, 1, Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> filter(1, 1, Duration.ofSeconds(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> filter(1, 1, null));
        assertThatNullPointerException().isThrownBy(() -> new RateLimitWebFilter(null, clock, problems, writer));
        assertThatNullPointerException().isThrownBy(() -> new RateLimitWebFilter(
                new TicketingProperties.RateLimit(true, 1, 1, Duration.ofSeconds(1)), null, problems, writer));
    }

    @Test
    void shouldMeasureBucketTimeWithTheApplicationClock() {
        RateLimitWebFilter.ClockTimeMeter timeMeter = new RateLimitWebFilter.ClockTimeMeter(clock);

        assertThat(timeMeter.isWallClockBased()).isTrue();
        assertThat(timeMeter.currentTimeNanos()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(clock.millis()));
        clock.advance(Duration.ofMillis(5));
        assertThat(timeMeter.currentTimeNanos()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(clock.millis()));
    }

    @Test
    void shouldRunRightAfterTheSecurityChain() {
        assertThat(filter(1, 1, Duration.ofSeconds(1)).getOrder()).isEqualTo(-90);
    }
}
