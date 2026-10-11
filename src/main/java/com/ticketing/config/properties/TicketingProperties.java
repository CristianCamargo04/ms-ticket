package com.ticketing.config.properties;

import com.ticketing.enums.RuntimeModeEnum;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ticketing")
public record TicketingProperties(
        @DefaultValue("ALL") RuntimeModeEnum runtimeMode,
        @DefaultValue Orders orders,
        @DefaultValue Events events,
        @DefaultValue Cache cache,
        @DefaultValue Sweeper sweeper,
        @DefaultValue Retry retry,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Messaging messaging,
        @DefaultValue Payment payment,
        @DefaultValue Security security,
        @DefaultValue Migrations migrations) {
    public record Orders(
            @DefaultValue("10m") Duration holdTtl,
            @DefaultValue("5m") Duration confirmationTimeout,
            @DefaultValue("30s") Duration republishAfter,
            @DefaultValue("10") int maxTicketsPerOrder,
            @DefaultValue("40") int maxComplimentaryPerRequest,
            @DefaultValue("3") int candidateFactor) {
    }

    public record Events(
            @DefaultValue("20000") int maxCapacity,
            @DefaultValue("1s") Duration availabilityStreamInterval,
            @DefaultValue("4") int ticketWriteConcurrency) {
    }

    public record Cache(
            @DefaultValue("2s") Duration eventTtl,
            @DefaultValue("10000") long eventMaxSize) {
    }

    public record Sweeper(
            @DefaultValue("15s") Duration interval,
            @DefaultValue("30s") Duration lease,
            @DefaultValue("100") int batchSize,
            @DefaultValue("8") int concurrency) {
    }

    public record Retry(
            @DefaultValue InventoryRetry inventory,
            @DefaultValue PublishRetry publish) {
        public record InventoryRetry(
                @DefaultValue("6") int maxAttempts,
                @DefaultValue("25ms") Duration minBackoff,
                @DefaultValue("1s") Duration maxBackoff,
                @DefaultValue("0.5") double jitter) {
        }

        public record PublishRetry(
                @DefaultValue("3") int maxAttempts,
                @DefaultValue("100ms") Duration minBackoff,
                @DefaultValue("1s") Duration maxBackoff,
                @DefaultValue("0.5") double jitter) {
        }
    }

    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("20") long capacity,
            @DefaultValue("20") long refillTokens,
            @DefaultValue("1s") Duration refillPeriod) {
    }

    public record Messaging(
            @DefaultValue("10") int maxMessages,
            @DefaultValue("20") int waitTimeSeconds,
            @DefaultValue("60") int visibilityTimeoutSeconds,
            @DefaultValue("16") int concurrency,
            @DefaultValue("2") int pollers) {
    }

    public record Payment(
            String baseUrl,
            @DefaultValue("1s") Duration connectTimeout,
            @DefaultValue("3s") Duration responseTimeout,
            @DefaultValue("4s") Duration callTimeout,
            @DefaultValue("2") int maxRetries,
            @DefaultValue("100ms") Duration retryBackoff,
            String apiKey) {
        static final String MASK = "******";

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        @Override
        public String toString() {
            return "Payment[baseUrl=" + baseUrl + ", connectTimeout=" + connectTimeout
                    + ", responseTimeout=" + responseTimeout + ", callTimeout=" + callTimeout
                    + ", maxRetries=" + maxRetries + ", retryBackoff=" + retryBackoff
                    + ", apiKey=" + (hasApiKey() ? MASK : "<none>") + "]";
        }
    }

    public record Security(
            @DefaultValue("ms-ticket") String audience,
            String issuer,
            @DefaultValue("roles") String rolesClaim) {
    }

    public record Migrations(@DefaultValue("false") boolean enabled) {
    }
}
