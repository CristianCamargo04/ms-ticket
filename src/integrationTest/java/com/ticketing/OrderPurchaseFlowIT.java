package com.ticketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketing.support.EndToEndEnvironment;
import com.ticketing.support.PaymentGatewayStub;
import com.ticketing.support.PaymentGatewayStub.RecordedCharge;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ticketing.runtime-mode=all",
    "ticketing.migrations.enabled=true",
    "ticketing.orders.hold-ttl=" + OrderPurchaseFlowIT.HOLD_TTL_SECONDS + "s",
    "ticketing.sweeper.interval=1s",

    "ticketing.retry.inventory.max-attempts=20",
    "management.endpoints.web.exposure.include=health,info,prometheus"})
@ActiveProfiles("ci")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class OrderPurchaseFlowIT {
    static final int HOLD_TTL_SECONDS = 6;

    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String CORRELATION_ID = "X-Correlation-Id";
    private static final String ADMIN = "admin-e2e";

    private static final EndToEndEnvironment ENVIRONMENT = EndToEndEnvironment.start();

    @TestBean
    private AwsCredentialsProvider awsCredentialsProvider;

    @LocalServerPort
    private int port;

    private WebTestClient api;
    private WebClient reactiveApi;

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        ENVIRONMENT.register(registry);
    }

    static AwsCredentialsProvider awsCredentialsProvider() {
        return EndToEndEnvironment.dummyCredentials();
    }

    @BeforeEach
    void clients() {
        String baseUrl = "http://localhost:" + port;
        api = WebTestClient.bindToServer().baseUrl(baseUrl).responseTimeout(HTTP_TIMEOUT).build();
        reactiveApi = WebClient.create(baseUrl);
    }

    @Test
    void shouldSellTicketsThroughReservationConfirmationAndPayment(CapturedOutput output) {
        String eventId = createEvent(10);
        String customer = uniqueCustomer();
        String correlationId = "e2e-" + UUID.randomUUID();

        OrderAccepted accepted = placeOrder(eventId, customer, 2, newKey(), correlationId);
        assertThat(accepted.status()).isEqualTo("AVAILABLE");
        assertThat(accepted.processingStatus()).isEqualTo("QUEUED");

        OrderView reserved = awaitOrder(accepted.orderId(), customer, order -> "RESERVED".equals(order.status()));
        assertThat(reserved.processingStatus()).isEqualTo("ACTIVE");
        assertThat(reserved.ticketIds()).hasSize(2).doesNotHaveDuplicates();
        assertThat(reserved.holdExpiresAt()).isAfter(Instant.now());
        assertThat(availability(eventId)).isEqualTo(new Counters(10, 8, 2, 0, 0, 0));

        confirm(accepted.orderId(), customer, PaymentGatewayStub.APPROVED_PREFIX + "_visa", correlationId)
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("PENDING_CONFIRMATION");

        OrderView sold = awaitOrder(accepted.orderId(), customer, order -> "SOLD".equals(order.status()));
        assertThat(sold.processingStatus()).isEqualTo("COMPLETED");
        assertThat(sold.ticketIds()).isEqualTo(reserved.ticketIds());
        assertThat(availability(eventId)).isEqualTo(new Counters(10, 8, 0, 0, 2, 0));
        for (String ticketId : sold.ticketIds()) {
            assertThat(transitions(eventId, ticketId)).extracting(TransitionView::toStatus)
                    .containsExactly("RESERVED", "PENDING_CONFIRMATION", "SOLD");
        }

        assertThat(chargesOf(accepted.orderId())).singleElement().satisfies(charge -> {
            assertThat(charge.idempotencyKey()).isEqualTo(accepted.orderId());
            assertThat(charge.authorization()).isEqualTo("Bearer " + EndToEndEnvironment.PAYMENT_API_KEY);
        });

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(logLinesWith(output, correlationId))
                .anySatisfy(line -> assertThat(line).contains("queued for event"))
                .anySatisfy(line -> assertThat(line).contains("holds tickets"))
                .anySatisfy(line -> assertThat(line).contains("confirmed, payment requested"))
                .anySatisfy(line -> assertThat(line).contains("payment approved")));
    }

    @Test
    void shouldReleaseTheTicketsWhenThePaymentIsDeclined() {
        String eventId = createEvent(4);
        String customer = uniqueCustomer();
        OrderAccepted accepted = placeOrder(eventId, customer, 1, newKey(), null);
        OrderView reserved = awaitOrder(accepted.orderId(), customer, order -> "RESERVED".equals(order.status()));

        confirm(accepted.orderId(), customer, "tok_declined_card", null).expectStatus().isAccepted();

        OrderView declined = awaitOrder(accepted.orderId(), customer,
                order -> "PAYMENT_DECLINED".equals(order.processingStatus()));
        assertThat(declined.status()).isEqualTo("AVAILABLE");
        assertThat(declined.reason()).isEqualTo("PAYMENT_DECLINED");
        assertThat(availability(eventId)).isEqualTo(new Counters(4, 4, 0, 0, 0, 0));
        String ticketId = reserved.ticketIds().getFirst();
        assertThat(transitions(eventId, ticketId)).extracting(TransitionView::toStatus)
                .containsExactly("RESERVED", "PENDING_CONFIRMATION", "AVAILABLE");
        assertThat(transitions(eventId, ticketId).getLast().reason()).isEqualTo("PAYMENT_DECLINED");
    }

    @Test
    void shouldNeverOversellWhenManyMoreCustomersThanTicketsBuyAtOnce() {
        int capacity = 5;
        int buyers = 20;
        String eventId = createEvent(capacity);
        List<Counters> samples = Collections.synchronizedList(new ArrayList<>());
        Disposable sampler = Flux.interval(Duration.ofMillis(50))
                .onBackpressureDrop()
                .concatMap(tick -> fetchAvailability(eventId))
                .subscribe(samples::add);
        try {
            List<PlacementResult> placements = Flux.range(1, buyers)
                    .flatMap(i -> placeOrderAsync(eventId, "rush-" + UUID.randomUUID()), buyers)
                    .collectList()
                    .block(HTTP_TIMEOUT);
            assertThat(placements).hasSize(buyers);
            List<PlacementResult> accepted = placements.stream().filter(PlacementResult::accepted).toList();
            long rejectedAtTheDoor = placements.stream().filter(PlacementResult::soldOut).count();
            assertThat(accepted.size() + rejectedAtTheDoor).isEqualTo(buyers);

            Map<String, OrderView> outcomes = await().atMost(ASYNC_TIMEOUT).pollInterval(Duration.ofMillis(200))
                    .until(() -> accepted.stream()
                                    .map(placement -> order(placement.orderId(), placement.customer()))
                                    .collect(Collectors.toMap(OrderView::orderId, order -> order)),
                            orders -> orders.values().stream()
                                    .noneMatch(order -> "QUEUED".equals(order.processingStatus())));

            List<OrderView> reserved = outcomes.values().stream()
                    .filter(order -> !order.ticketIds().isEmpty()).toList();
            List<OrderView> rejected = outcomes.values().stream()
                    .filter(order -> "REJECTED".equals(order.processingStatus())).toList();
            assertThat(reserved).hasSize(capacity);
            assertThat(reserved).allSatisfy(order -> assertThat(order.ticketIds()).hasSize(1));
            assertThat(reserved.stream().flatMap(order -> order.ticketIds().stream()).collect(Collectors.toSet()))
                    .as("no ticket is held by two orders").hasSize(capacity);
            assertThat(rejected.size() + rejectedAtTheDoor).isEqualTo(buyers - capacity);
            assertThat(rejected).allSatisfy(order -> {
                assertThat(order.reason()).isEqualTo("SOLD_OUT");
                assertThat(order.status()).isEqualTo("AVAILABLE");
            });

            assertThat(availability(eventId).total()).isEqualTo(capacity);
        } finally {
            sampler.dispose();
        }

        assertThat(samples).isNotEmpty().allSatisfy(counters -> {
            assertThat(counters.available()).isBetween(0, capacity);
            assertThat(counters.reserved()).isBetween(0, capacity);
            assertThat(counters.total()).isEqualTo(capacity);
        });
        assertThat(ENVIRONMENT.deadLetters(ENVIRONMENT.reservationQueue())).isZero();
    }

    @Test
    void shouldExpireUnconfirmedReservationsAndReleaseTheirTickets() {
        String eventId = createEvent(3);
        String customer = uniqueCustomer();
        OrderAccepted accepted = placeOrder(eventId, customer, 2, newKey(), null);
        OrderView reserved = awaitOrder(accepted.orderId(), customer, order -> "RESERVED".equals(order.status()));
        assertThat(availability(eventId)).isEqualTo(new Counters(3, 1, 2, 0, 0, 0));

        OrderView expired = awaitOrder(accepted.orderId(), customer,
                order -> "EXPIRED".equals(order.processingStatus()));
        assertThat(Instant.now()).isAfterOrEqualTo(reserved.holdExpiresAt());
        assertThat(expired.status()).isEqualTo("AVAILABLE");
        assertThat(expired.reason()).isEqualTo("HOLD_EXPIRED");
        assertThat(availability(eventId)).isEqualTo(new Counters(3, 3, 0, 0, 0, 0));
        for (String ticketId : reserved.ticketIds()) {
            List<TransitionView> history = transitions(eventId, ticketId);
            assertThat(history).extracting(TransitionView::toStatus).containsExactly("RESERVED", "AVAILABLE");
            assertThat(history.getLast().reason()).isEqualTo("HOLD_EXPIRED");
        }

        confirm(accepted.orderId(), customer, PaymentGatewayStub.APPROVED_PREFIX, null)
                .expectStatus().isEqualTo(HttpStatus.GONE)
                .expectBody().jsonPath("$.errorCode").isEqualTo("HOLD_EXPIRED");
        assertThat(chargesOf(accepted.orderId())).isEmpty();
    }

    @Test
    void shouldReplayIdempotentOrdersAndRejectAReusedKeyWithAnotherBody() {
        String eventId = createEvent(10);
        String customer = uniqueCustomer();
        String key = newKey();

        OrderAccepted first = placeOrder(eventId, customer, 1, key, null);
        OrderAccepted replay = placeOrder(eventId, customer, 1, key, null);
        assertThat(replay.orderId()).isEqualTo(first.orderId());

        placeOrderRequest(eventId, customer, 3, key, null)
                .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.errorCode").isEqualTo("IDEMPOTENCY_KEY_REUSED");

        OrderAccepted otherCustomer = placeOrder(eventId, uniqueCustomer(), 1, key, null);
        assertThat(otherCustomer.orderId()).isNotEqualTo(first.orderId());

        awaitOrder(first.orderId(), customer, order -> "RESERVED".equals(order.status()));
        OrderAccepted lateReplay = placeOrder(eventId, customer, 1, key, null);
        assertThat(lateReplay.orderId()).isEqualTo(first.orderId());
        assertThat(lateReplay.processingStatus()).isNotEqualTo("QUEUED");
        assertThat(availability(eventId).total()).isEqualTo(10);
    }

    @Test
    void shouldExposeCircuitBreakerMetricsAndPublicHealthProbes() {
        api.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
        String metrics = api.get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(metrics).containsPattern("resilience4j_circuitbreaker_state\\{[^}]*name=\"paymentGateway\"");
    }

    private String createEvent(int capacity) {
        Map<String, Object> body = Map.of(
                "name", "E2E concert " + UUID.randomUUID(),
                "venue", "Arena",
                "eventDate", Instant.now().plus(30, ChronoUnit.DAYS).toString(),
                "totalCapacity", capacity,
                "ticketPrice", new BigDecimal("49.90"),
                "currency", "USD");
        EventView event = api.post().uri("/api/v1/events")
                .headers(headers -> headers.setBearerAuth(ENVIRONMENT.issuer().adminToken(ADMIN)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(EventView.class).returnResult().getResponseBody();
        assertThat(event).isNotNull();
        assertThat(event.status()).isEqualTo("ON_SALE");
        assertThat(event.availableTickets()).isEqualTo(capacity);
        return event.eventId();
    }

    private WebTestClient.ResponseSpec placeOrderRequest(String eventId, String customer, int quantity, String key,
                                                         String correlationId) {
        return api.post().uri("/api/v1/events/{eventId}/orders", eventId)
                .headers(headers -> {
                    headers.setBearerAuth(ENVIRONMENT.issuer().customerToken(customer));
                    headers.set(IDEMPOTENCY_KEY, key);
                    if (correlationId != null) {
                        headers.set(CORRELATION_ID, correlationId);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("quantity", quantity))
                .exchange();
    }

    private OrderAccepted placeOrder(String eventId, String customer, int quantity, String key,
                                     String correlationId) {
        OrderAccepted accepted = placeOrderRequest(eventId, customer, quantity, key, correlationId)
                .expectStatus().isAccepted()
                .expectHeader().exists(HttpHeaders.LOCATION)
                .expectBody(OrderAccepted.class).returnResult().getResponseBody();
        assertThat(accepted).isNotNull();
        return accepted;
    }

    private Mono<PlacementResult> placeOrderAsync(String eventId, String customer) {
        return reactiveApi.post().uri("/api/v1/events/{eventId}/orders", eventId)
                .headers(headers -> {
                    headers.setBearerAuth(ENVIRONMENT.issuer().customerToken(customer));
                    headers.set(IDEMPOTENCY_KEY, newKey());
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("quantity", 1))
                .exchangeToMono(response -> response.bodyToMono(PlacementBody.class)
                        .map(body -> new PlacementResult(customer, response.statusCode().value(), body.orderId(),
                                body.errorCode())));
    }

    private WebTestClient.ResponseSpec confirm(String orderId, String customer, String paymentToken,
                                               String correlationId) {
        return api.post().uri("/api/v1/orders/{orderId}/confirmation", orderId)
                .headers(headers -> {
                    headers.setBearerAuth(ENVIRONMENT.issuer().customerToken(customer));
                    headers.set(IDEMPOTENCY_KEY, newKey());
                    if (correlationId != null) {
                        headers.set(CORRELATION_ID, correlationId);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("paymentToken", paymentToken))
                .exchange();
    }

    private OrderView order(String orderId, String customer) {
        OrderView order = api.get().uri("/api/v1/orders/{orderId}", orderId)
                .headers(headers -> headers.setBearerAuth(ENVIRONMENT.issuer().customerToken(customer)))
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrderView.class).returnResult().getResponseBody();
        assertThat(order).isNotNull();
        return order;
    }

    private OrderView awaitOrder(String orderId, String customer, Predicate<OrderView> condition) {
        return awaitAsync().until(() -> order(orderId, customer), condition::test);
    }

    private Counters availability(String eventId) {
        Counters counters = fetchAvailability(eventId).block(HTTP_TIMEOUT);
        assertThat(counters).isNotNull();
        return counters;
    }

    private Mono<Counters> fetchAvailability(String eventId) {
        return reactiveApi.get().uri("/api/v1/events/{eventId}/availability", eventId)
                .retrieve()
                .bodyToMono(Counters.class);
    }

    private List<TransitionView> transitions(String eventId, String ticketId) {
        return api.get().uri("/api/v1/events/{eventId}/tickets/{ticketId}/transitions", eventId, ticketId)
                .headers(headers -> headers.setBearerAuth(ENVIRONMENT.issuer().adminToken(ADMIN)))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(TransitionView.class).returnResult().getResponseBody();
    }

    private static List<RecordedCharge> chargesOf(String orderId) {
        return ENVIRONMENT.paymentGateway().charges().stream()
                .filter(charge -> orderId.equals(charge.orderId()))
                .toList();
    }

    private static List<String> logLinesWith(CapturedOutput output, String correlationId) {
        String field = "\"correlationId\":\"" + correlationId + "\"";
        return output.getAll().lines().filter(line -> line.contains(field)).toList();
    }

    private static ConditionFactory awaitAsync() {
        return await().atMost(ASYNC_TIMEOUT).pollInterval(Duration.ofMillis(100));
    }

    private static String uniqueCustomer() {
        return "customer-" + UUID.randomUUID();
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    record EventView(String eventId, String status, int availableTickets) {
    }

    record OrderAccepted(String orderId, String status, String processingStatus, String statusUrl) {
    }

    record OrderView(String orderId, String status, String processingStatus, String reason, List<String> ticketIds,
                     Instant holdExpiresAt) {
        OrderView {
            ticketIds = ticketIds == null ? List.of() : List.copyOf(ticketIds);
        }
    }

    record TransitionView(String ticketId, String fromStatus, String toStatus, String reason) {
    }

    record Counters(int totalCapacity, int available, int reserved, int pendingConfirmation, int sold,
                    int complimentary) {
        int total() {
            return available + reserved + pendingConfirmation + sold + complimentary;
        }
    }

    record PlacementBody(String orderId, String errorCode) {
    }

    record PlacementResult(String customer, int status, String orderId, String errorCode) {
        boolean accepted() {
            return status == HttpStatus.ACCEPTED.value();
        }

        boolean soldOut() {
            return status == HttpStatus.CONFLICT.value() && "SOLD_OUT".equals(errorCode);
        }
    }
}
