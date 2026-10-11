package com.ticketing.config.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.config.web.ProblemDetailResponseWriter;
import com.ticketing.domain.exception.RateLimitExceededException;
import com.ticketing.presentation.context.impl.RequestContextResolverImpl;
import com.ticketing.presentation.impl.error.ProblemDetailFactory;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Slf4j
public class RateLimitWebFilter implements WebFilter, Ordered {
    public static final int ORDER = -90;

    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    static final long MAX_TRACKED_CALLERS = 100_000;

    static final String API_PREFIX = "/api/";
    static final String SUBJECT_KEY_PREFIX = "subject:";
    static final String IP_KEY_PREFIX = "ip:";
    static final String UNKNOWN_CLIENT = "unknown";

    private final TicketingProperties.RateLimit settings;
    private final TimeMeter timeMeter;
    private final Cache<String, Bucket> buckets;
    private final ProblemDetailFactory problems;
    private final ProblemDetailResponseWriter writer;

    public RateLimitWebFilter(TicketingProperties.RateLimit settings, Clock clock, ProblemDetailFactory problems,
                              ProblemDetailResponseWriter writer) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (settings.capacity() <= 0 || settings.refillTokens() <= 0) {
            throw new IllegalArgumentException("rate-limit capacity and refill-tokens must be positive");
        }
        if (settings.refillPeriod() == null || settings.refillPeriod().isNegative()
                || settings.refillPeriod().isZero()) {
            throw new IllegalArgumentException("rate-limit refill-period must be positive");
        }
        this.timeMeter = new ClockTimeMeter(Objects.requireNonNull(clock, "clock"));
        this.problems = Objects.requireNonNull(problems, "problems");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_CALLERS)
                .expireAfterAccess(timeToFullRefill(settings))
                .build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isLimited(exchange.getRequest())) {
            return chain.filter(exchange);
        }
        return callerKey(exchange).flatMap(key -> {
            ConsumptionProbe probe = buckets.get(key, _ -> newBucket()).tryConsumeAndReturnRemaining(1);
            if (probe.isConsumed()) {
                exchange.getResponse().getHeaders().set(REMAINING_HEADER, Long.toString(probe.getRemainingTokens()));
                return chain.filter(exchange);
            }
            return reject(exchange, retryAfterSeconds(probe.getNanosToWaitForRefill()));
        });
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    long trackedCallers() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }

    static boolean isLimited(ServerHttpRequest request) {
        HttpMethod method = request.getMethod();
        return (HttpMethod.POST.equals(method) || HttpMethod.DELETE.equals(method))
                && request.getPath().pathWithinApplication().value().startsWith(API_PREFIX);
    }

    static long retryAfterSeconds(long nanosToWait) {
        long seconds = TimeUnit.NANOSECONDS.toSeconds(nanosToWait);
        if (TimeUnit.SECONDS.toNanos(seconds) < nanosToWait) {
            seconds++;
        }
        return Math.max(1, seconds);
    }

    static Duration timeToFullRefill(TicketingProperties.RateLimit settings) {
        long periods = (settings.capacity() + settings.refillTokens() - 1) / settings.refillTokens();
        return settings.refillPeriod().multipliedBy(periods);
    }

    private Mono<String> callerKey(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .map(Principal::getName)
                .filter(name -> !name.isBlank())
                .map(name -> SUBJECT_KEY_PREFIX + name)
                .switchIfEmpty(Mono.fromSupplier(() -> IP_KEY_PREFIX + Objects.requireNonNullElse(
                        RequestContextResolverImpl.clientIpOf(exchange.getRequest()), UNKNOWN_CLIENT)));
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(settings.capacity())
                        .refillGreedy(settings.refillTokens(), settings.refillPeriod()))
                .withCustomTimePrecision(timeMeter)
                .build();
    }

    private Mono<Void> reject(ServerWebExchange exchange, long retryAfterSeconds) {
        log.debug("Rate limit exceeded on {} {}", exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value());
        RateLimitExceededException exception = new RateLimitExceededException(retryAfterSeconds);
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return writer.write(exchange, problems.forException(exception, exchange));
    }

    record ClockTimeMeter(Clock clock) implements TimeMeter {
        @Override
        public long currentTimeNanos() {
            return TimeUnit.MILLISECONDS.toNanos(clock.millis());
        }

        @Override
        public boolean isWallClockBased() {
            return true;
        }
    }
}
