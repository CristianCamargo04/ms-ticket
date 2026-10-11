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

@Configuration(proxyBeanMethods = false)
public class PaymentGatewayClientConfig {
    static final String CONNECTION_POOL_NAME = "payment-gateway";

    static final int MAX_CONNECTIONS = 100;

    static final Duration MAX_IDLE_TIME = Duration.ofSeconds(20);

    static final Duration EVICTION_INTERVAL = Duration.ofSeconds(30);

    @Bean
    public PaymentGatewaySettings paymentGatewaySettings(TicketingProperties properties) {
        TicketingProperties.Payment payment = properties.payment();
        return new PaymentGatewaySettings(payment.callTimeout(), payment.maxRetries(), payment.retryBackoff());
    }

    @Bean(destroyMethod = "dispose")
    public ConnectionProvider paymentGatewayConnectionProvider(TicketingProperties properties) {
        return ConnectionProvider.builder(CONNECTION_POOL_NAME)
                .maxConnections(MAX_CONNECTIONS)
                .pendingAcquireTimeout(properties.payment().connectTimeout())
                .maxIdleTime(MAX_IDLE_TIME)
                .evictInBackground(EVICTION_INTERVAL)
                .build();
    }

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
            String apiKey = payment.apiKey().strip();
            builder.defaultHeaders(headers -> headers.setBearerAuth(apiKey));
        }
        return builder.build();
    }

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
