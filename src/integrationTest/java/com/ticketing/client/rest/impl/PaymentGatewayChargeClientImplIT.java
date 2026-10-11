package com.ticketing.client.rest.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.client.mapper.PaymentGatewayChargeClientMapper;
import com.ticketing.client.rest.api.PaymentGatewayChargeApi;
import com.ticketing.config.client.PaymentGatewayClientConfig;
import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.config.resilience.ResilienceConfig;
import com.ticketing.domain.exception.PaymentGatewayException;
import com.ticketing.domain.external_request.payment.PaymentChargeRequest;
import com.ticketing.domain.order.PaymentOutcome;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the payment client against a real HTTP gateway (WireMock container), with the web client, settings and
 * circuit breaker built by the production configuration classes and wired by Spring.
 */
@Testcontainers
class PaymentGatewayChargeClientImplIT {

    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(20);
    private static final int WIREMOCK_PORT = 8080;
    private static final PaymentChargeRequest REQUEST = new PaymentChargeRequest("order-ok", "order-ok", "customer-1",
            new BigDecimal("150.5"), "USD", "tok_ok");

    @Container
    private static final GenericContainer<?> WIREMOCK =
            new GenericContainer<>(DockerImageName.parse("wiremock/wiremock:3.13.2"))
                    .withExposedPorts(WIREMOCK_PORT)
                    .waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200));

    private static final HttpClient ADMIN = HttpClient.newHttpClient();

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TicketingProperties.class)
    static class PropertiesConfig {
    }

    private static String baseUrl() {
        return "http://" + WIREMOCK.getHost() + ":" + WIREMOCK.getMappedPort(WIREMOCK_PORT);
    }

    private static String admin(String path, String json) throws IOException, InterruptedException {
        HttpResponse<String> response = ADMIN.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/__admin" + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("admin %s: %s", path, response.body()).isBetween(200, 299);
        return response.body();
    }

    private static void stub(String mapping) throws IOException, InterruptedException {
        admin("/mappings", mapping);
    }

    private static int requestCount(String method, String url) throws IOException, InterruptedException {
        String body = admin("/requests/count", "{\"method\":\"" + method + "\",\"url\":\"" + url + "\"}");
        return JsonMapper.builder().build().readTree(body).get("count").asInt();
    }

    private static void withClient(Consumer<PaymentGatewayChargeApi> scenario) {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class, PaymentGatewayClientConfig.class, ResilienceConfig.class)
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                .withBean(PaymentGatewayChargeClientMapper.class)
                .withBean(PaymentGatewayChargeClientImpl.class)
                .withPropertyValues(
                        "ticketing.payment.base-url=" + baseUrl(),
                        "ticketing.payment.connect-timeout=1s",
                        "ticketing.payment.response-timeout=2s",
                        "ticketing.payment.call-timeout=1s",
                        "ticketing.payment.max-retries=2",
                        "ticketing.payment.retry-backoff=20ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CircuitBreaker.class).getName()).isEqualTo("paymentGateway");
                    scenario.accept(context.getBean(PaymentGatewayChargeApi.class));
                });
    }

    @BeforeEach
    void resetGateway() throws IOException, InterruptedException {
        admin("/reset", "{}");
    }

    @Test
    void shouldChargeWithTheIdempotencyKeyHeaderAndJsonBody() throws IOException, InterruptedException {
        stub("""
                {"request": {"method": "POST", "url": "/v1/charges",
                             "headers": {"Idempotency-Key": {"equalTo": "order-ok"},
                                         "Content-Type": {"contains": "application/json"}},
                             "bodyPatterns": [{"equalToJson": {"orderId": "order-ok", "customerId": "customer-1",
                                                               "amount": 150.5, "currency": "USD",
                                                               "paymentToken": "tok_ok"}}]},
                 "response": {"status": 201, "headers": {"Content-Type": "application/json"},
                              "jsonBody": {"paymentId": "pay-1", "status": "APPROVED"}}}""");

        withClient(client -> StepVerifier.create(client.charge(REQUEST))
                .expectNext(new PaymentOutcome.Approved("pay-1"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT));
    }

    @Test
    void shouldReturnADeclinedCharge() throws IOException, InterruptedException {
        stub("""
                {"request": {"method": "POST", "url": "/v1/charges"},
                 "response": {"status": 200, "headers": {"Content-Type": "application/json"},
                              "jsonBody": {"status": "DECLINED", "declineReason": "card_expired"}}}""");

        withClient(client -> StepVerifier.create(client.charge(REQUEST))
                .expectNext(new PaymentOutcome.Declined("card_expired"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT));
    }

    @Test
    void shouldRetryATransientServerErrorWithTheSameIdempotencyKey() throws IOException, InterruptedException {
        stub("""
                {"scenarioName": "flaky", "requiredScenarioState": "Started", "newScenarioState": "recovered",
                 "request": {"method": "POST", "url": "/v1/charges",
                             "headers": {"Idempotency-Key": {"equalTo": "order-ok"}}},
                 "response": {"status": 503}}""");
        stub("""
                {"scenarioName": "flaky", "requiredScenarioState": "recovered",
                 "request": {"method": "POST", "url": "/v1/charges",
                             "headers": {"Idempotency-Key": {"equalTo": "order-ok"}}},
                 "response": {"status": 200, "headers": {"Content-Type": "application/json"},
                              "jsonBody": {"paymentId": "pay-2", "status": "APPROVED"}}}""");

        withClient(client -> StepVerifier.create(client.charge(REQUEST))
                .expectNext(new PaymentOutcome.Approved("pay-2"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT));

        assertThat(requestCount("POST", "/v1/charges")).isEqualTo(2);
    }

    @Test
    void shouldGiveUpOnASlowGatewayWithAPaymentGatewayException() throws IOException, InterruptedException {
        stub("""
                {"request": {"method": "POST", "url": "/v1/charges"},
                 "response": {"status": 200, "fixedDelayMilliseconds": 5000,
                              "headers": {"Content-Type": "application/json"},
                              "jsonBody": {"paymentId": "pay-3", "status": "APPROVED"}}}""");

        withClient(client -> StepVerifier.create(client.charge(REQUEST))
                .expectError(PaymentGatewayException.class)
                .verify(VERIFY_TIMEOUT));

        assertThat(requestCount("POST", "/v1/charges")).isEqualTo(3);
    }

    @Test
    void shouldLookUpAnExistingCharge() throws IOException, InterruptedException {
        stub("""
                {"request": {"method": "GET", "url": "/v1/charges/order-ok"},
                 "response": {"status": 200, "headers": {"Content-Type": "application/json"},
                              "jsonBody": {"paymentId": "pay-1", "status": "APPROVED"}}}""");

        withClient(client -> StepVerifier.create(client.findCharge("order-ok"))
                .expectNext(new PaymentOutcome.Approved("pay-1"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT));
    }

    @Test
    void shouldReportAnUnknownChargeAsNotFound() {
        withClient(client -> StepVerifier.create(client.findCharge("order-unknown"))
                .expectNext(new PaymentOutcome.NotFound())
                .expectComplete()
                .verify(VERIFY_TIMEOUT));
    }
}
