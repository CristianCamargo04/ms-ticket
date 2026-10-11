package com.ticketing.presentation.impl;

import static com.ticketing.presentation.impl.TestJwts.ADMIN;
import static com.ticketing.presentation.impl.TestJwts.CUSTOMER;
import static com.ticketing.presentation.impl.TestJwts.admin;
import static com.ticketing.presentation.impl.TestJwts.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import com.ticketing.business.usecase.CancelOrderUseCase;
import com.ticketing.business.usecase.ConfirmOrderUseCase;
import com.ticketing.business.usecase.GetOrderStatusUseCase;
import com.ticketing.business.usecase.PlaceOrderUseCase;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.exception.EventNotFoundException;
import com.ticketing.domain.exception.EventNotOnSaleException;
import com.ticketing.domain.exception.IdempotencyKeyConflictException;
import com.ticketing.domain.exception.InsufficientInventoryException;
import com.ticketing.domain.exception.InvalidOrderStateException;
import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.exception.OrderNotFoundException;
import com.ticketing.domain.exception.ReservationExpiredException;
import com.ticketing.domain.exception.TicketingException;
import com.ticketing.domain.internal_request.CancelOrderRequest;
import com.ticketing.domain.internal_request.ConfirmOrderRequest;
import com.ticketing.domain.internal_request.GetOrderRequest;
import com.ticketing.domain.internal_request.PlaceOrderRequest;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.presentation.OrderRestController;
import com.ticketing.presentation.context.RequestContext;
import com.ticketing.presentation.impl.idempotency.RequestFingerprintCalculator;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = OrderRestControllerImpl.class)
@Import(RestControllerTestConfig.class)
class OrderRestControllerImplTest {

    private static final Instant NOW = RestControllerTestConfig.NOW;
    private static final String KEY = "6f1c2a9e-0b7d-4c55-9a51-3f2b8e4d7c10";
    private static final String ORDER_ID = "0b1f6d2c-8a43-5e7f-9c21-4d5e6f708192";
    private static final String PLACE_ORDER = "/api/v1/events/e-1/orders";
    private static final String ORDER = "/api/v1/orders/" + ORDER_ID;

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private PlaceOrderUseCase placeOrderUseCase;

    @MockitoBean
    private GetOrderStatusUseCase getOrderStatusUseCase;

    @MockitoBean
    private ConfirmOrderUseCase confirmOrderUseCase;

    @MockitoBean
    private CancelOrderUseCase cancelOrderUseCase;

    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    private static OrderData queuedOrder() {
        return OrderData.newQueued(ORDER_ID, "e-1", CUSTOMER, OrderTypeEnum.STANDARD, 2, "hash",
                NOW.plusSeconds(30), NOW);
    }

    private static OrderData reservedOrder() {
        return queuedOrder().withReservation(List.of("T-000001", "T-000002"), NOW.plusSeconds(600), NOW);
    }

    private WebTestClient.ResponseSpec placeOrder(String body, String idempotencyKey) {
        return webTestClient.mutateWith(customer(CUSTOMER)).post().uri(PLACE_ORDER)
                .headers(headers -> {
                    if (idempotencyKey != null) {
                        headers.set(OrderRestController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
                    }
                    headers.set(RequestContext.CORRELATION_ID_HEADER, "corr-1");
                })
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange();
    }

    @Test
    void shouldAcceptOrderForTheAuthenticatedCustomer() {
        given(placeOrderUseCase.execute(any())).willReturn(Mono.just(queuedOrder()));

        placeOrder("{\"quantity\":2}", KEY)
                .expectStatus().isAccepted()
                .expectHeader().location(ORDER)
                .expectHeader().valueEquals(RequestContext.CORRELATION_ID_HEADER, "corr-1")
                .expectBody()
                .jsonPath("$.orderId").isEqualTo(ORDER_ID)
                .jsonPath("$.status").isEqualTo("AVAILABLE")
                .jsonPath("$.processingStatus").isEqualTo("QUEUED")
                .jsonPath("$.statusUrl").isEqualTo(ORDER);

        ArgumentCaptor<PlaceOrderRequest> request = ArgumentCaptor.forClass(PlaceOrderRequest.class);
        verify(placeOrderUseCase).execute(request.capture());
        assertThat(request.getValue()).isEqualTo(new PlaceOrderRequest("e-1", CUSTOMER, 2, KEY,
                new RequestFingerprintCalculator().placeOrder("e-1", 2), "corr-1"));
    }

    @Test
    void shouldTakeTheCustomerFromTheTokenNeverFromTheBody() {
        given(placeOrderUseCase.execute(any())).willReturn(Mono.just(queuedOrder()));

        placeOrder("{\"quantity\":2,\"customerId\":\"someone-else\"}", KEY)
                .expectStatus().isAccepted();

        ArgumentCaptor<PlaceOrderRequest> request = ArgumentCaptor.forClass(PlaceOrderRequest.class);
        verify(placeOrderUseCase).execute(request.capture());
        assertThat(request.getValue().customerId()).isEqualTo(CUSTOMER);
    }

    @Test
    void shouldGenerateCorrelationIdWhenAbsent() {
        given(placeOrderUseCase.execute(any())).willReturn(Mono.just(queuedOrder()));

        webTestClient.mutateWith(customer(CUSTOMER)).post().uri(PLACE_ORDER)
                .header(OrderRestController.IDEMPOTENCY_KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"quantity\":1}")
                .exchange()
                .expectStatus().isAccepted();

        ArgumentCaptor<PlaceOrderRequest> request = ArgumentCaptor.forClass(PlaceOrderRequest.class);
        verify(placeOrderUseCase).execute(request.capture());
        assertThat(request.getValue().correlationId()).matches("^[0-9a-f-]{36}$");
    }

    @Test
    void shouldLetAdministratorsPlaceOrders() {
        given(placeOrderUseCase.execute(any())).willReturn(Mono.just(queuedOrder()));

        webTestClient.mutateWith(admin(ADMIN)).post().uri(PLACE_ORDER)
                .header(OrderRestController.IDEMPOTENCY_KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"quantity\":1}")
                .exchange()
                .expectStatus().isAccepted();
    }

    @Test
    void shouldRequireTheIdempotencyKey() {
        placeOrder("{\"quantity\":2}", null)
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("INVALID_IDEMPOTENCY_KEY")
                .jsonPath("$.title").isEqualTo("INVALID_IDEMPOTENCY_KEY")
                .jsonPath("$.correlationId").isEqualTo("corr-1");
        verifyNoInteractions(placeOrderUseCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "has spaces in it", "under_score_key", "x"})
    void shouldRejectMalformedIdempotencyKeys(String key) {
        placeOrder("{\"quantity\":2}", key)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("INVALID_IDEMPOTENCY_KEY");
        verifyNoInteractions(placeOrderUseCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"quantity\":0}", "{\"quantity\":11}", "{\"quantity\":-3}"})
    void shouldValidateTheOrderBody(String body) {
        placeOrder(body, KEY)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.errors.length()").isEqualTo(1)
                .jsonPath("$.errors[0].field").isEqualTo("quantity");
        verifyNoInteractions(placeOrderUseCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"quantity\":\"two\"}", "[]"})
    void shouldRejectUnreadableOrderBodies(String body) {
        placeOrder(body, KEY)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.detail").isEqualTo("The request body is malformed");
        verifyNoInteractions(placeOrderUseCase);
    }

    @Test
    void shouldRequireAuthenticationToPlaceOrders() {
        webTestClient.post().uri(PLACE_ORDER)
                .header(OrderRestController.IDEMPOTENCY_KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"quantity\":1}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("UNAUTHORIZED");
        verifyNoInteractions(placeOrderUseCase);
    }

    @Test
    void shouldForbidCallersWithoutRole() {
        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt.subject("nobody"))).post().uri(PLACE_ORDER)
                .header(OrderRestController.IDEMPOTENCY_KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"quantity\":1}")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"")
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("FORBIDDEN");
        verifyNoInteractions(placeOrderUseCase);
    }

    static Stream<Arguments> placeOrderFailures() {
        return Stream.of(
                Arguments.of(new IdempotencyKeyConflictException(KEY), 422, "IDEMPOTENCY_KEY_REUSED"),
                Arguments.of(new InsufficientInventoryException("e-1", 2, 0), 409, "SOLD_OUT"),
                Arguments.of(new EventNotOnSaleException("e-1", EventStatusEnum.CLOSED), 409, "EVENT_NOT_ON_SALE"),
                Arguments.of(new EventNotFoundException("e-1"), 404, "EVENT_NOT_FOUND"));
    }

    @ParameterizedTest
    @MethodSource("placeOrderFailures")
    void shouldMapPlaceOrderBusinessErrors(TicketingException error, int status, String errorCode) {
        given(placeOrderUseCase.execute(any())).willReturn(Mono.error(error));

        placeOrder("{\"quantity\":2}", KEY)
                .expectStatus().isEqualTo(status)
                .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.status").isEqualTo(status)
                .jsonPath("$.errorCode").isEqualTo(errorCode)
                .jsonPath("$.detail").isEqualTo(error.getMessage());
    }

    @Test
    void shouldAskClientsToRetryTransientFailures() {
        given(placeOrderUseCase.execute(any()))
                .willReturn(Mono.error(new InventoryConflictException("Inventory contention, retry later")));

        placeOrder("{\"quantity\":2}", KEY)
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1")
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("INVENTORY_CONFLICT");
    }

    @Test
    void shouldReadOwnOrderAsNonPrivilegedCustomer() {
        given(getOrderStatusUseCase.execute(new GetOrderRequest(ORDER_ID, CUSTOMER, false)))
                .willReturn(Mono.just(reservedOrder()));

        webTestClient.mutateWith(customer(CUSTOMER)).get().uri(ORDER)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.orderId").isEqualTo(ORDER_ID)
                .jsonPath("$.eventId").isEqualTo("e-1")
                .jsonPath("$.type").isEqualTo("STANDARD")
                .jsonPath("$.status").isEqualTo("RESERVED")
                .jsonPath("$.processingStatus").isEqualTo("ACTIVE")
                .jsonPath("$.ticketIds.length()").isEqualTo(2)
                .jsonPath("$.holdExpiresAt").isEqualTo("2026-10-10T10:10:00Z");
    }

    @Test
    void shouldReadAnyOrderAsAdministrator() {
        given(getOrderStatusUseCase.execute(new GetOrderRequest(ORDER_ID, ADMIN, true)))
                .willReturn(Mono.just(reservedOrder()));

        webTestClient.mutateWith(admin(ADMIN)).get().uri(ORDER)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void shouldHideOrdersOfOtherCustomers() {
        given(getOrderStatusUseCase.execute(new GetOrderRequest(ORDER_ID, "customer-2", false)))
                .willReturn(Mono.error(new OrderNotFoundException(ORDER_ID)));

        webTestClient.mutateWith(customer("customer-2")).get().uri(ORDER)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    void shouldRequireAuthenticationToReadOrders() {
        webTestClient.get().uri(ORDER)
                .exchange()
                .expectStatus().isUnauthorized();
        verifyNoInteractions(getOrderStatusUseCase);
    }

    @Test
    void shouldConfirmReservation() {
        OrderData pending = reservedOrder().withPendingConfirmation(NOW.plusSeconds(300), NOW);
        given(confirmOrderUseCase.execute(any())).willReturn(Mono.just(pending));

        webTestClient.mutateWith(customer(CUSTOMER)).post().uri(ORDER + "/confirmation")
                .header(OrderRestController.IDEMPOTENCY_KEY_HEADER, KEY)
                .header(RequestContext.CORRELATION_ID_HEADER, "corr-9")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"paymentToken\":\"tok_visa-123\"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("PENDING_CONFIRMATION");

        verify(confirmOrderUseCase).execute(new ConfirmOrderRequest(ORDER_ID, CUSTOMER, "tok_visa-123", "corr-9"));
    }

    @Test
    void shouldValidateThePaymentToken() {
        webTestClient.mutateWith(customer(CUSTOMER)).post().uri(ORDER + "/confirmation")
                .header(OrderRestController.IDEMPOTENCY_KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"paymentToken\":\"4111 1111 1111 1111\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.errors[0].field").isEqualTo("paymentToken")
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent()))
                        .doesNotContain("4111"));
        verifyNoInteractions(confirmOrderUseCase);
    }

    @Test
    void shouldRequireTheIdempotencyKeyToConfirm() {
        webTestClient.mutateWith(customer(CUSTOMER)).post().uri(ORDER + "/confirmation")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"paymentToken\":\"tok_1\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("INVALID_IDEMPOTENCY_KEY");
        verifyNoInteractions(confirmOrderUseCase);
    }

    static Stream<Arguments> confirmFailures() {
        return Stream.of(
                Arguments.of(new ReservationExpiredException(ORDER_ID), 410, "HOLD_EXPIRED"),
                Arguments.of(new InvalidOrderStateException(ORDER_ID, "order is not reserved"), 409,
                        "INVALID_ORDER_STATE"),
                Arguments.of(new OrderNotFoundException(ORDER_ID), 404, "ORDER_NOT_FOUND"));
    }

    @ParameterizedTest
    @MethodSource("confirmFailures")
    void shouldMapConfirmationErrors(TicketingException error, int status, String errorCode) {
        given(confirmOrderUseCase.execute(any())).willReturn(Mono.error(error));

        webTestClient.mutateWith(customer(CUSTOMER)).post().uri(ORDER + "/confirmation")
                .header(OrderRestController.IDEMPOTENCY_KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"paymentToken\":\"tok_1\"}")
                .exchange()
                .expectStatus().isEqualTo(status)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(errorCode);
    }

    @Test
    void shouldCancelReservation() {
        OrderData cancelled = reservedOrder().withRelease(OrderProcessingStatusEnum.CANCELLED,
                "CUSTOMER_CANCELLATION", NOW);
        given(cancelOrderUseCase.execute(new CancelOrderRequest(ORDER_ID, CUSTOMER)))
                .willReturn(Mono.just(cancelled));

        webTestClient.mutateWith(customer(CUSTOMER)).delete().uri(ORDER + "/reservation")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("AVAILABLE")
                .jsonPath("$.processingStatus").isEqualTo("CANCELLED")
                .jsonPath("$.reason").isEqualTo("CUSTOMER_CANCELLATION");
    }

    @Test
    void shouldReportCancellationOfForeignOrdersAsNotFound() {
        given(cancelOrderUseCase.execute(any())).willReturn(Mono.error(new OrderNotFoundException(ORDER_ID)));

        webTestClient.mutateWith(customer("customer-2")).delete().uri(ORDER + "/reservation")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void shouldRequireAuthenticationToCancel() {
        webTestClient.delete().uri(ORDER + "/reservation")
                .exchange()
                .expectStatus().isUnauthorized();
        verifyNoInteractions(cancelOrderUseCase);
    }
}
