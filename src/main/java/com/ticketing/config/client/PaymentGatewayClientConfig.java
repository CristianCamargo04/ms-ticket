package com.ticketing.config.client;

import com.ticketing.client.rest.impl.PaymentGatewaySettings;
import com.ticketing.config.properties.TicketingProperties;
import io.micrometer.observation.ObservationRegistry;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import tools.jackson.databind.json.JsonMapper;

/**
 * Payment gateway HTTP client: a {@link WebClient} on Reactor Netty bound to {@code ticketing.payment.base-url},
 * with TCP connect and response timeouts, its own connection pool, the application JSON mapper and HTTP client
 * observations (metrics/tracing) when an {@link ObservationRegistry} is available. When
 * {@code ticketing.payment.api-key} ({@code TICKETING_PAYMENT_APIKEY}, a Secrets Manager secret in AWS) is non-blank,
 * every call carries {@code Authorization: Bearer <api-key>}; the key is never logged. The per-call policy (overall
 * timeout, retries, circuit breaker) lives in the client and is configured by {@link PaymentGatewaySettings}.
 */
@Configuration(proxyBeanMethods = false)
public class PaymentGatewayClientConfig {

    /** Name of the dedicated connection pool (Reactor Netty metrics). */
    static final String CONNECTION_POOL_NAME = "payment-gateway";

    /** Maximum open connections to the gateway. */
    static final int MAX_CONNECTIONS = 100;

    /** Idle connections are closed before the gateway (or a load balancer) silently drops them. */
    static final Duration MAX_IDLE_TIME = Duration.ofSeconds(20);

    /** Background eviction period of idle connections. */
    static final Duration EVICTION_INTERVAL = Duration.ofSeconds(30);

    /**
     * Resilience settings of the payment client ({@code ticketing.payment.call-timeout|max-retries|retry-backoff}).
     *
     * @param properties application settings
     * @return the payment gateway settings
     */
    @Bean
    public PaymentGatewaySettings paymentGatewaySettings(TicketingProperties properties) {
        TicketingProperties.Payment payment = properties.payment();
        return new PaymentGatewaySettings(payment.callTimeout(), payment.maxRetries(), payment.retryBackoff());
    }

    /**
     * Connection pool dedicated to the gateway, so a slow gateway can never exhaust connections used elsewhere.
     * Pending acquisitions wait at most the connect timeout.
     *
     * @param properties application settings
     * @return the connection provider, disposed with the application context
     */
    @Bean(destroyMethod = "dispose")
    public ConnectionProvider paymentGatewayConnectionProvider(TicketingProperties properties) {
        return ConnectionProvider.builder(CONNECTION_POOL_NAME)
                .maxConnections(MAX_CONNECTIONS)
                .pendingAcquireTimeout(properties.payment().connectTimeout())
                .maxIdleTime(MAX_IDLE_TIME)
                .evictInBackground(EVICTION_INTERVAL)
                .build();
    }

    /**
     * The payment gateway web client.
     *
     * @param properties                       application settings
     * @param paymentGatewayConnectionProvider the dedicated connection pool
     * @param jsonMapper                       the application JSON mapper
     * @param observationRegistry              HTTP client observations, when available
     * @return the web client, sending the bearer API key when one is configured
     * @throws IllegalStateException when the base URL is missing or not an absolute http(s) URL
     */
    @Bean
    public WebClient paymentGatewayWebClient(TicketingProperties properties,
                                             ConnectionProvider paymentGatewayConnectionProvider,
                                             JsonMapper jsonMapper,
                                             ObjectProvider<ObservationRegistry> observationRegistry) {
        TicketingProperties.Payment payment = properties.payment();
        HttpClient httpClient = HttpClient.create(paymentGatewayConnectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(payment.connectTimeout().toMillis()))
                .responseTimeout(payment.responseTimeout());
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(validatedBaseUrl(payment.baseUrl()))
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> {
                    codecs.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(jsonMapper));
                    codecs.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(jsonMapper));
                })
                .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
        if (payment.hasApiKey()) {
            // Default header of every call; the key itself is never logged (see TicketingProperties.Payment).
            String apiKey = payment.apiKey().strip();
            builder.defaultHeaders(headers -> headers.setBearerAuth(apiKey));
        }
        return builder.build();
    }

    /**
     * Fails fast at startup on a missing or malformed gateway URL.
     *
     * @param baseUrl the configured base URL
     * @return the base URL without a trailing slash
     */
    static String validatedBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("ticketing.payment.base-url must be set");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ticketing.payment.base-url is not a valid URL", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            throw new IllegalStateException("ticketing.payment.base-url must be an absolute http(s) URL");
        }
        String normalized = uri.toString();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
