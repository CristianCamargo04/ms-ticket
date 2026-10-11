package com.ticketing.client.rest.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.client.mapper.PaymentGatewayChargeClientMapper;
import com.ticketing.domain.exception.PaymentGatewayException;
import com.ticketing.domain.external_request.payment.PaymentChargeRequest;
import com.ticketing.domain.order.PaymentOutcome;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import mockwebserver3.junit5.StartStop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PaymentGatewayChargeClientImplTest {

    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(20);
    /** Generous call timeout: only the dedicated timeout test relies on timing, the others must never time out. */
    private static final PaymentGatewaySettings SETTINGS =
            new PaymentGatewaySettings(Duration.ofSeconds(3), 2, Duration.ofMillis(10));
    private static final PaymentChargeRequest REQUEST = new PaymentChargeRequest("order-1", "order-1", "customer-1",
            new BigDecimal("150.00"), "USD", "tok_secret");
    private static final String APPROVED = """
            {"paymentId":"pay-1","status":"APPROVED","declineReason":null,"extra":"ignored"}""";
    private static final String DECLINED = """
            {"paymentId":null,"status":"DECLINED","declineReason":"insufficient_funds"}""";

    @StartStop
    private final MockWebServer server = new MockWebServer();

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private CircuitBreaker circuitBreaker;
    private PaymentGatewayChargeClientImpl client;

    @BeforeEach
    void setUp() {
        circuitBreaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .recordException(PaymentGatewayChargeClientImpl::isTransientFailure)
                .build());
        client = newClient(server.url("/").toString(), SETTINGS);
    }

    private PaymentGatewayChargeClientImpl newClient(String baseUrl, PaymentGatewaySettings settings) {
        return new PaymentGatewayChargeClientImpl(WebClient.builder().baseUrl(baseUrl).build(), settings,
                circuitBreaker, new PaymentGatewayChargeClientMapper());
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse.Builder()
                .code(code)
                .addHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body)
                .build();
    }

    private static MockResponse status(int code) {
        return new MockResponse.Builder().code(code).build();
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        return request;
    }

    private static void assertGatewayFailure(Throwable error, Class<? extends Throwable> cause) {
        assertThat(error).isInstanceOf(PaymentGatewayException.class).hasCauseInstanceOf(cause);
        assertThat(((PaymentGatewayException) error).retryable()).isTrue();
        assertThat(error.getMessage()).doesNotContain("tok_secret");
    }

    @Test
    void shouldPostChargeWithIdempotencyKeyAndReturnApproved() throws InterruptedException {
        server.enqueue(json(201, APPROVED));

        StepVerifier.create(client.charge(REQUEST))
                .expectNext(new PaymentOutcome.Approved("pay-1"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        RecordedRequest request = takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getTarget()).isEqualTo("/v1/charges");
        assertThat(request.getHeaders().get("Idempotency-Key")).isEqualTo("order-1");
        assertThat(request.getHeaders().get(HttpHeaders.CONTENT_TYPE)).startsWith("application/json");
        assertThat(request.getHeaders().get(HttpHeaders.ACCEPT)).isEqualTo("application/json");
        JsonNode body = jsonMapper.readTree(request.getBody().string(StandardCharsets.UTF_8));
        assertThat(body.get("orderId").asString()).isEqualTo("order-1");
        assertThat(body.get("customerId").asString()).isEqualTo("customer-1");
        assertThat(body.get("amount").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(body.get("currency").asString()).isEqualTo("USD");
        assertThat(body.get("paymentToken").asString()).isEqualTo("tok_secret");
        assertThat(body.has("idempotencyKey")).isFalse();
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    void shouldReturnDeclinedWithReason() {
        server.enqueue(json(200, DECLINED));

        StepVerifier.create(client.charge(REQUEST))
                .expectNext(new PaymentOutcome.Declined("insufficient_funds"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    @Test
    void shouldResolvePathsAgainstABaseUrlWithAPathPrefix() throws InterruptedException {
        server.enqueue(json(200, APPROVED));
        PaymentGatewayChargeClientImpl prefixedClient = newClient(server.url("/gateway").toString(), SETTINGS);

        StepVerifier.create(prefixedClient.charge(REQUEST))
                .expectNext(new PaymentOutcome.Approved("pay-1"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        assertThat(takeRequest().getTarget()).isEqualTo("/gateway/v1/charges");
    }

    @Test
    void shouldRetryOnServerErrorAndThenSucceedWithTheSameIdempotencyKey() throws InterruptedException {
        server.enqueue(status(500));
        server.enqueue(json(200, APPROVED));

        StepVerifier.create(client.charge(REQUEST))
                .expectNext(new PaymentOutcome.Approved("pay-1"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(takeRequest().getHeaders().get("Idempotency-Key")).isEqualTo("order-1");
        assertThat(takeRequest().getHeaders().get("Idempotency-Key")).isEqualTo("order-1");
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void shouldFailWithPaymentGatewayExceptionWhenServerErrorsPersist() {
        server.enqueue(status(503));
        server.enqueue(status(500));
        server.enqueue(status(502));

        StepVerifier.create(client.charge(REQUEST))
                .expectErrorSatisfies(error -> {
                    assertGatewayFailure(error, WebClientResponseException.class);
                    assertThat(error).hasMessageContaining("HTTP 502");
                })
                .verify(VERIFY_TIMEOUT);

        assertThat(server.getRequestCount()).isEqualTo(1 + SETTINGS.maxRetries());
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void shouldNotRetryClientErrorsAndReportThemAsGatewayFailures() {
        server.enqueue(status(400));

        StepVerifier.create(client.charge(REQUEST))
                .expectErrorSatisfies(error -> {
                    assertGatewayFailure(error, WebClientResponseException.BadRequest.class);
                    assertThat(error).hasMessageContaining("HTTP 400");
                })
                .verify(VERIFY_TIMEOUT);

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    void shouldTreatNotFoundOnChargeAsAFailure() {
        server.enqueue(status(404));

        StepVerifier.create(client.charge(REQUEST))
                .expectErrorSatisfies(error -> assertGatewayFailure(error, WebClientResponseException.NotFound.class))
                .verify(VERIFY_TIMEOUT);
    }

    @Test
    void shouldTimeOutSlowResponsesAfterRetrying() throws InterruptedException {
        PaymentGatewaySettings fastTimeout =
                new PaymentGatewaySettings(Duration.ofMillis(300), 1, Duration.ofMillis(5));
        PaymentGatewayChargeClientImpl impatientClient = newClient(server.url("/").toString(), fastTimeout);
        MockResponse slow = new MockResponse.Builder(json(200, APPROVED)).headersDelay(3, TimeUnit.SECONDS).build();
        server.enqueue(slow);
        server.enqueue(slow);

        StepVerifier.create(impatientClient.charge(REQUEST))
                .expectErrorSatisfies(error -> {
                    assertGatewayFailure(error, TimeoutException.class);
                    assertThat(error).hasMessageContaining("timed out");
                })
                .verify(VERIFY_TIMEOUT);

        takeRequest();
        takeRequest();
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void shouldRetryConnectionErrorsAndFailWithPaymentGatewayException() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        PaymentGatewayChargeClientImpl unreachableClient = newClient("http://localhost:" + closedPort, SETTINGS);

        StepVerifier.create(unreachableClient.charge(REQUEST))
                .expectErrorSatisfies(error -> {
                    assertGatewayFailure(error, WebClientRequestException.class);
                    assertThat(error).hasMessageContaining("I/O error");
                })
                .verify(VERIFY_TIMEOUT);

        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void shouldFailFastWithoutCallingTheGatewayWhenTheCircuitIsOpen() {
        circuitBreaker.transitionToOpenState();

        StepVerifier.create(client.charge(REQUEST))
                .expectErrorSatisfies(error -> {
                    assertGatewayFailure(error, CallNotPermittedException.class);
                    assertThat(error).hasMessageContaining("circuit breaker is open");
                })
                .verify(VERIFY_TIMEOUT);
        StepVerifier.create(client.findCharge("order-1"))
                .expectErrorSatisfies(error -> assertGatewayFailure(error, CallNotPermittedException.class))
                .verify(VERIFY_TIMEOUT);

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void shouldFailOnEmptyChargeResponseWithoutRetrying() {
        server.enqueue(status(200));

        StepVerifier.create(client.charge(REQUEST))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(PaymentGatewayException.class)
                        .hasMessageContaining("empty charge response"))
                .verify(VERIFY_TIMEOUT);

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldFailOnUnknownStatusOrMalformedResponse() {
        server.enqueue(json(200, "{\"paymentId\":\"pay-1\",\"status\":\"PENDING\"}"));
        server.enqueue(json(200, "{not json"));
        server.enqueue(json(200, "{\"paymentId\":\" \",\"status\":\"APPROVED\"}"));

        StepVerifier.create(client.charge(REQUEST))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(PaymentGatewayException.class)
                        .hasMessageContaining("unknown charge status"))
                .verify(VERIFY_TIMEOUT);
        StepVerifier.create(client.charge(REQUEST))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(PaymentGatewayException.class)
                        .hasMessageContaining("unexpected"))
                .verify(VERIFY_TIMEOUT);
        StepVerifier.create(client.charge(REQUEST))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(PaymentGatewayException.class)
                        .hasCauseInstanceOf(IllegalArgumentException.class))
                .verify(VERIFY_TIMEOUT);

        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void shouldLookUpAnApprovedCharge() throws InterruptedException {
        server.enqueue(json(200, APPROVED));

        StepVerifier.create(client.findCharge("order-1"))
                .expectNext(new PaymentOutcome.Approved("pay-1"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        RecordedRequest request = takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getTarget()).isEqualTo("/v1/charges/order-1");
        assertThat(request.getHeaders().get(HttpHeaders.ACCEPT)).isEqualTo("application/json");
    }

    @Test
    void shouldLookUpADeclinedCharge() {
        server.enqueue(json(200, DECLINED));

        StepVerifier.create(client.findCharge("order-1"))
                .expectNext(new PaymentOutcome.Declined("insufficient_funds"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    @Test
    void shouldReturnNotFoundWhenTheChargeDoesNotExist() {
        server.enqueue(status(HttpStatus.NOT_FOUND.value()));

        StepVerifier.create(client.findCharge("order-1"))
                .expectNext(new PaymentOutcome.NotFound())
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    void shouldEncodeTheIdempotencyKeyInTheLookupPath() throws InterruptedException {
        server.enqueue(status(404));

        StepVerifier.create(client.findCharge("a/b c"))
                .expectNext(new PaymentOutcome.NotFound())
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        assertThat(takeRequest().getTarget()).isEqualTo("/v1/charges/a%2Fb%20c");
    }

    @Test
    void shouldRetryLookupOnServerError() {
        server.enqueue(status(500));
        server.enqueue(status(404));

        StepVerifier.create(client.findCharge("order-1"))
                .expectNext(new PaymentOutcome.NotFound())
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void shouldFailLookupOnPersistentServerErrorsOrClientErrors() {
        server.enqueue(status(500));
        server.enqueue(status(500));
        server.enqueue(status(500));
        server.enqueue(status(401));

        StepVerifier.create(client.findCharge("order-1"))
                .expectErrorSatisfies(error -> assertGatewayFailure(error, WebClientResponseException.class))
                .verify(VERIFY_TIMEOUT);
        StepVerifier.create(client.findCharge("order-1"))
                .expectErrorSatisfies(error -> assertGatewayFailure(error,
                        WebClientResponseException.Unauthorized.class))
                .verify(VERIFY_TIMEOUT);

        assertThat(server.getRequestCount()).isEqualTo(4);
    }

    @Test
    void shouldFailOnEmptyLookupResponse() {
        server.enqueue(status(200));

        StepVerifier.create(client.findCharge("order-1"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(PaymentGatewayException.class)
                        .hasMessageContaining("empty charge lookup response"))
                .verify(VERIFY_TIMEOUT);
    }

    @Test
    void shouldNotCallTheGatewayBeforeSubscription() {
        Mono<PaymentOutcome> charge = client.charge(REQUEST);
        Mono<PaymentOutcome> lookup = client.findCharge("order-1");

        assertThat(charge).isNotNull();
        assertThat(lookup).isNotNull();
        assertThat(server.getRequestCount()).isZero();
    }

    private static WebClientResponseException responseError(int code) {
        return WebClientResponseException.create(HttpStatusCode.valueOf(code), "error", new HttpHeaders(),
                new byte[0], StandardCharsets.UTF_8, null);
    }

    @Test
    void shouldClassifyTransientFailures() {
        assertThat(PaymentGatewayChargeClientImpl.isTransientFailure(responseError(503))).isTrue();
        assertThat(PaymentGatewayChargeClientImpl.isTransientFailure(responseError(429))).isFalse();
        assertThat(PaymentGatewayChargeClientImpl.isTransientFailure(new TimeoutException())).isTrue();
        assertThat(PaymentGatewayChargeClientImpl.isTransientFailure(new IllegalStateException())).isFalse();
        assertThat(PaymentGatewayChargeClientImpl.isTransientFailure(null)).isFalse();
    }

    private static PaymentChargeRequest requestWithIdempotencyKey(String idempotencyKey) {
        return new PaymentChargeRequest(idempotencyKey, "order-1", "customer-1", new BigDecimal("150.00"), "USD",
                "tok_secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void shouldNeverSendAChargeOrLookupWithABlankIdempotencyKey(String blankKey) {
        server.enqueue(status(503));
        server.enqueue(json(200, APPROVED));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> client.charge(requestWithIdempotencyKey(blankKey)))
                .withMessageContaining("idempotencyKey");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> client.findCharge(blankKey))
                .withMessageContaining("idempotencyKey");

        assertThat(server.getRequestCount()).isZero();
        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void shouldNeverSendAChargeWithoutIdempotencyKey() {
        server.enqueue(status(503));
        server.enqueue(json(200, APPROVED));

        assertThatNullPointerException()
                .isThrownBy(() -> client.charge(requestWithIdempotencyKey(null)))
                .withMessageContaining("idempotencyKey");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void shouldSurfaceAMissingIdempotencyKeyAsAnErrorSignalInsideAPipeline() {
        StepVerifier.create(Mono.just(requestWithIdempotencyKey(" ")).flatMap(client::charge))
                .expectError(IllegalArgumentException.class)
                .verify(VERIFY_TIMEOUT);

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void shouldRejectNullArguments() {
        WebClient webClient = WebClient.create();
        PaymentGatewayChargeClientMapper mapper = new PaymentGatewayChargeClientMapper();

        assertThatNullPointerException().isThrownBy(() -> client.charge(null));
        assertThatNullPointerException().isThrownBy(() -> client.findCharge(null));
        assertThatNullPointerException().isThrownBy(
                () -> new PaymentGatewayChargeClientImpl(null, SETTINGS, circuitBreaker, mapper));
        assertThatNullPointerException().isThrownBy(
                () -> new PaymentGatewayChargeClientImpl(webClient, null, circuitBreaker, mapper));
        assertThatNullPointerException().isThrownBy(
                () -> new PaymentGatewayChargeClientImpl(webClient, SETTINGS, null, mapper));
        assertThatNullPointerException().isThrownBy(
                () -> new PaymentGatewayChargeClientImpl(webClient, SETTINGS, circuitBreaker, null));
    }
}
