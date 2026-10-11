package com.ticketing.config.properties;

import com.ticketing.enums.RuntimeModeEnum;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Application settings bound from {@code ticketing.*}. Every section has defaults, so an empty configuration yields
 * a complete object. Layers never read this class: the composition root turns it into settings records owned by
 * each layer (e.g. {@code OrderPolicy}).
 *
 * @param runtimeMode which roles this instance runs ({@code all}, {@code api}, {@code worker})
 * @param orders      order rules
 * @param events      event rules
 * @param cache       event cache
 * @param sweeper     sweepers
 * @param retry       retry policies
 * @param rateLimit   API rate limiting
 * @param messaging   SQS consumers
 * @param payment     payment gateway client
 * @param security    JWT validation
 * @param migrations  DynamoDB migration runner
 */
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

    /**
     * Order rules ({@code ticketing.orders.*}).
     *
     * @param holdTtl                    reservation hold duration
     * @param confirmationTimeout        maximum wait for the payment result after a confirmation
     * @param republishAfter             delay before a still-queued order is republished
     * @param maxTicketsPerOrder         maximum tickets per standard order
     * @param maxComplimentaryPerRequest maximum tickets per complimentary request
     * @param candidateFactor            candidate tickets read per requested ticket
     */
    public record Orders(
            @DefaultValue("10m") Duration holdTtl,
            @DefaultValue("5m") Duration confirmationTimeout,
            @DefaultValue("30s") Duration republishAfter,
            @DefaultValue("10") int maxTicketsPerOrder,
            @DefaultValue("40") int maxComplimentaryPerRequest,
            @DefaultValue("3") int candidateFactor) {
    }

    /**
     * Event rules ({@code ticketing.events.*}).
     *
     * @param maxCapacity                maximum tickets per event
     * @param availabilityStreamInterval polling interval of the availability stream
     * @param ticketWriteConcurrency     parallel batch writes when generating tickets
     */
    public record Events(
            @DefaultValue("20000") int maxCapacity,
            @DefaultValue("1s") Duration availabilityStreamInterval,
            @DefaultValue("4") int ticketWriteConcurrency) {
    }

    /**
     * Event cache ({@code ticketing.cache.*}).
     *
     * @param eventTtl     time to live of a cached event
     * @param eventMaxSize maximum cached events
     */
    public record Cache(
            @DefaultValue("2s") Duration eventTtl,
            @DefaultValue("10000") long eventMaxSize) {
    }

    /**
     * Sweepers ({@code ticketing.sweeper.*}).
     *
     * @param interval    delay between two runs
     * @param lease       distributed lease duration of a run
     * @param batchSize   maximum due orders per deadline type and run
     * @param concurrency maximum orders processed in parallel
     */
    public record Sweeper(
            @DefaultValue("15s") Duration interval,
            @DefaultValue("30s") Duration lease,
            @DefaultValue("100") int batchSize,
            @DefaultValue("8") int concurrency) {
    }

    /**
     * Retry policies ({@code ticketing.retry.*}).
     *
     * @param inventory retries of inventory transaction conflicts
     * @param publish   retries of queue publications
     */
    public record Retry(
            @DefaultValue InventoryRetry inventory,
            @DefaultValue PublishRetry publish) {

        /**
         * Inventory retries ({@code ticketing.retry.inventory.*}).
         *
         * @param maxAttempts maximum retries after the first failure
         * @param minBackoff  first delay
         * @param maxBackoff  maximum delay
         * @param jitter      jitter factor (0..1)
         */
        public record InventoryRetry(
                @DefaultValue("6") int maxAttempts,
                @DefaultValue("25ms") Duration minBackoff,
                @DefaultValue("1s") Duration maxBackoff,
                @DefaultValue("0.5") double jitter) {
        }

        /**
         * Publication retries ({@code ticketing.retry.publish.*}).
         *
         * @param maxAttempts maximum retries after the first failure
         * @param minBackoff  first delay
         * @param maxBackoff  maximum delay
         * @param jitter      jitter factor (0..1)
         */
        public record PublishRetry(
                @DefaultValue("3") int maxAttempts,
                @DefaultValue("100ms") Duration minBackoff,
                @DefaultValue("1s") Duration maxBackoff,
                @DefaultValue("0.5") double jitter) {
        }
    }

    /**
     * API rate limiting ({@code ticketing.rate-limit.*}).
     *
     * @param enabled      whether the limiter is active
     * @param capacity     bucket capacity per principal (or client IP)
     * @param refillTokens tokens added every refill period
     * @param refillPeriod refill period
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("20") long capacity,
            @DefaultValue("20") long refillTokens,
            @DefaultValue("1s") Duration refillPeriod) {
    }

    /**
     * SQS consumers ({@code ticketing.messaging.*}).
     *
     * @param maxMessages              messages per receive call (1..10)
     * @param waitTimeSeconds          long polling wait (0..20)
     * @param visibilityTimeoutSeconds visibility timeout of received messages
     * @param concurrency              messages processed in parallel per queue
     * @param pollers                  concurrent receive loops per queue
     */
    public record Messaging(
            @DefaultValue("10") int maxMessages,
            @DefaultValue("20") int waitTimeSeconds,
            @DefaultValue("60") int visibilityTimeoutSeconds,
            @DefaultValue("16") int concurrency,
            @DefaultValue("2") int pollers) {
    }

    /**
     * Payment gateway client ({@code ticketing.payment.*}).
     *
     * @param baseUrl         gateway base URL (required, no default)
     * @param connectTimeout  TCP connect timeout
     * @param responseTimeout response timeout of a call
     * @param callTimeout     overall timeout of a single call (connect + response + body)
     * @param maxRetries      retries on 5xx responses and timeouts (0 disables retries)
     * @param retryBackoff    first backoff between retries
     */
    public record Payment(
            String baseUrl,
            @DefaultValue("1s") Duration connectTimeout,
            @DefaultValue("3s") Duration responseTimeout,
            @DefaultValue("4s") Duration callTimeout,
            @DefaultValue("2") int maxRetries,
            @DefaultValue("100ms") Duration retryBackoff) {
    }

    /**
     * JWT validation ({@code ticketing.security.*}).
     *
     * @param audience   required {@code aud} claim value
     * @param issuer     required {@code iss} claim value (required, no default)
     * @param rolesClaim claim holding the role names
     */
    public record Security(
            @DefaultValue("ms-ticket") String audience,
            String issuer,
            @DefaultValue("roles") String rolesClaim) {
    }

    /**
     * DynamoDB migration runner ({@code ticketing.migrations.*}); enabled only in {@code local} and {@code ci}.
     *
     * @param enabled whether pending migrations are applied at startup
     */
    public record Migrations(@DefaultValue("false") boolean enabled) {
    }
}
