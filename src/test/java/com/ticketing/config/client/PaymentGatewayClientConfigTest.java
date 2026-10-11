package com.ticketing.config.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.ticketing.client.rest.impl.PaymentGatewaySettings;
import com.ticketing.config.properties.TicketingProperties;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.junit5.StartStop;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class PaymentGatewayClientConfigTest {

    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(10);

    @StartStop
    private final MockWebServer server = new MockWebServer();

    private final PaymentGatewayClientConfig config = new PaymentGatewayClientConfig();

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TicketingProperties.class)
    static class PropertiesConfig {
    }

    /** Body decoded by the gateway web client in the tests. */
    record Pong(String paymentId, String status) {
    }

    private static TicketingProperties properties(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("ticketing", TicketingProperties.class);
    }

    private static ObjectProvider<ObservationRegistry> noObservationRegistry() {
        return new StaticApplicationContext().getBeanProvider(ObservationRegistry.class);
    }

    private WebClient webClient(Map<String, String> values, JsonMapper jsonMapper, ConnectionProvider provider) {
        Map<String, String> withBaseUrl = new HashMap<>(values);
        withBaseUrl.put("ticketing.payment.base-url", server.url("/").toString());
        return config.paymentGatewayWebClient(properties(withBaseUrl), provider, jsonMapper, noObservationRegistry());
    }

    @Test
    void shouldBuildPaymentGatewaySettingsFromDefaults() {
        PaymentGatewaySettings settings = config.paymentGatewaySettings(properties(Map.of()));

        assertThat(settings).isEqualTo(new PaymentGatewaySettings(Duration.ofSeconds(4), 2, Duration.ofMillis(100)));
    }

    @Test
    void shouldBuildPaymentGatewaySettingsFromCustomValues() {
        PaymentGatewaySettings settings = config.paymentGatewaySettings(properties(Map.of(
                "ticketing.payment.call-timeout", "750ms",
                "ticketing.payment.max-retries", "0",
                "ticketing.payment.retry-backoff", "20ms")));

        assertThat(settings).isEqualTo(new PaymentGatewaySettings(Duration.ofMillis(750), 0, Duration.ofMillis(20)));
    }

    @Test
    void shouldCreateADedicatedConnectionPool() {
        ConnectionProvider provider = config.paymentGatewayConnectionProvider(properties(Map.of()));
        try {
            assertThat(provider.name()).isEqualTo(PaymentGatewayClientConfig.CONNECTION_POOL_NAME);
            assertThat(provider.maxConnections()).isEqualTo(PaymentGatewayClientConfig.MAX_CONNECTIONS);
        } finally {
            provider.dispose();
        }
        assertThat(provider.isDisposed()).isTrue();
    }

    @Test
    void shouldCallTheBaseUrlAndDecodeWithTheApplicationJsonMapper() throws InterruptedException {
        JsonMapper snakeCase = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        ConnectionProvider provider = config.paymentGatewayConnectionProvider(properties(Map.of()));
        server.enqueue(new MockResponse.Builder()
                .addHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{\"payment_id\":\"pay-1\",\"status\":\"APPROVED\"}")
                .build());
        try {
            WebClient webClient = webClient(Map.of(), snakeCase, provider);

            StepVerifier.create(webClient.get().uri("/v1/ping").retrieve().bodyToMono(Pong.class))
                    .expectNext(new Pong("pay-1", "APPROVED"))
                    .expectComplete()
                    .verify(VERIFY_TIMEOUT);

            assertThat(server.takeRequest(1, TimeUnit.SECONDS).getTarget()).isEqualTo("/v1/ping");
        } finally {
            provider.dispose();
        }
    }

    @Test
    void shouldFailSlowResponsesAfterTheResponseTimeout() {
        ConnectionProvider provider = config.paymentGatewayConnectionProvider(properties(Map.of()));
        server.enqueue(new MockResponse.Builder().body("late").headersDelay(2, TimeUnit.SECONDS).build());
        try {
            WebClient webClient = webClient(Map.of("ticketing.payment.response-timeout", "200ms"),
                    JsonMapper.builder().build(), provider);

            StepVerifier.create(webClient.get().uri("/v1/slow").retrieve().bodyToMono(String.class))
                    .expectError(WebClientRequestException.class)
                    .verify(VERIFY_TIMEOUT);
        } finally {
            provider.dispose();
        }
    }

    @Test
    void shouldNormalizeAValidBaseUrl() {
        assertThat(PaymentGatewayClientConfig.validatedBaseUrl("http://localhost:8089"))
                .isEqualTo("http://localhost:8089");
        assertThat(PaymentGatewayClientConfig.validatedBaseUrl(" https://gateway.example.com/api/ "))
                .isEqualTo("https://gateway.example.com/api");
        assertThat(PaymentGatewayClientConfig.validatedBaseUrl("HTTPS://gateway.example.com"))
                .isEqualTo("HTTPS://gateway.example.com");
    }

    @Test
    void shouldRejectAMissingOrMalformedBaseUrl() {
        assertThatIllegalStateException().isThrownBy(() -> PaymentGatewayClientConfig.validatedBaseUrl(null))
                .withMessageContaining("must be set");
        assertThatIllegalStateException().isThrownBy(() -> PaymentGatewayClientConfig.validatedBaseUrl("  "))
                .withMessageContaining("must be set");
        assertThatIllegalStateException().isThrownBy(() -> PaymentGatewayClientConfig.validatedBaseUrl("not a url"))
                .withMessageContaining("not a valid URL");
        assertThatIllegalStateException()
                .isThrownBy(() -> PaymentGatewayClientConfig.validatedBaseUrl("ftp://gateway.example.com"))
                .withMessageContaining("absolute http(s) URL");
        assertThatIllegalStateException().isThrownBy(() -> PaymentGatewayClientConfig.validatedBaseUrl("/v1"))
                .withMessageContaining("absolute http(s) URL");
        assertThatIllegalStateException().isThrownBy(() -> PaymentGatewayClientConfig.validatedBaseUrl("http:/v1"))
                .withMessageContaining("absolute http(s) URL");
    }

    @Test
    void shouldRegisterTheClientBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class, PaymentGatewayClientConfig.class)
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                .withBean(ObservationRegistry.class, ObservationRegistry::create)
                .withPropertyValues("ticketing.payment.base-url=http://localhost:8089")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PaymentGatewaySettings.class);
                    assertThat(context).hasSingleBean(ConnectionProvider.class);
                    assertThat(context).getBean("paymentGatewayWebClient").isInstanceOf(WebClient.class);
                });
    }

    @Test
    void shouldFailStartupWithoutABaseUrl() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class, PaymentGatewayClientConfig.class)
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("ticketing.payment.base-url must be set"));
    }
}
