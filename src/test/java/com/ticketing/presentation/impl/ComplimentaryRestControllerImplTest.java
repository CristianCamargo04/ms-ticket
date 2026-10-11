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

import com.ticketing.business.usecase.IssueComplimentaryTicketsUseCase;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.exception.EventNotOnSaleException;
import com.ticketing.domain.exception.IdempotencyKeyConflictException;
import com.ticketing.domain.internal_request.IssueComplimentaryRequest;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.presentation.OrderRestController;
import com.ticketing.presentation.context.RequestContext;
import com.ticketing.presentation.impl.idempotency.RequestFingerprintCalculator;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = ComplimentaryRestControllerImpl.class)
@Import(RestControllerTestConfig.class)
class ComplimentaryRestControllerImplTest {

    private static final String KEY = "comp-2026-10-10-0001";
    private static final String ORDER_ID = "7a0c3b8e-1d2f-5a4b-8c6d-9e0f1a2b3c4d";
    private static final String URI = "/api/v1/events/e-1/complimentary-tickets";
    private static final String BODY = "{\"beneficiaryId\":\"vip-7\",\"quantity\":4}";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private IssueComplimentaryTicketsUseCase issueComplimentaryTicketsUseCase;

    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    private static OrderData queuedComplimentary() {
        return OrderData.newQueued(ORDER_ID, "e-1", "vip-7", OrderTypeEnum.COMPLIMENTARY, 4, "hash",
                RestControllerTestConfig.NOW.plusSeconds(30), RestControllerTestConfig.NOW);
    }

    private WebTestClient.ResponseSpec issue(WebTestClient client, String body, String key) {
        return client.post().uri(URI)
                .headers(headers -> {
                    if (key != null) {
                        headers.set(OrderRestController.IDEMPOTENCY_KEY_HEADER, key);
                    }
                    headers.set(RequestContext.CORRELATION_ID_HEADER, "corr-c");
                })
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange();
    }

    @Test
    void shouldQueueComplimentaryTicketsIssuedByTheAdministrator() {
        given(issueComplimentaryTicketsUseCase.execute(any())).willReturn(Mono.just(queuedComplimentary()));

        issue(webTestClient.mutateWith(admin(ADMIN)), BODY, KEY)
                .expectStatus().isAccepted()
                .expectHeader().location("/api/v1/orders/" + ORDER_ID)
                .expectBody()
                .jsonPath("$.orderId").isEqualTo(ORDER_ID)
                .jsonPath("$.processingStatus").isEqualTo("QUEUED")
                .jsonPath("$.statusUrl").isEqualTo("/api/v1/orders/" + ORDER_ID);

        verify(issueComplimentaryTicketsUseCase).execute(new IssueComplimentaryRequest("e-1", "vip-7", 4, ADMIN, KEY,
                new RequestFingerprintCalculator().issueComplimentary("e-1", "vip-7", 4), "corr-c"));
    }

    @Test
    void shouldForbidCustomers() {
        issue(webTestClient.mutateWith(customer(CUSTOMER)), BODY, KEY)
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("FORBIDDEN");
        verifyNoInteractions(issueComplimentaryTicketsUseCase);
    }

    @Test
    void shouldRequireAuthentication() {
        issue(webTestClient, BODY, KEY)
                .expectStatus().isUnauthorized();
        verifyNoInteractions(issueComplimentaryTicketsUseCase);
    }

    @Test
    void shouldValidateTheBody() {
        issue(webTestClient.mutateWith(admin(ADMIN)), "{\"beneficiaryId\":\"\",\"quantity\":41}", KEY)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.errors[*].field").value(fields -> assertThat(fields)
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .containsExactly("beneficiaryId", "quantity"));
        verifyNoInteractions(issueComplimentaryTicketsUseCase);
    }

    @Test
    void shouldRequireTheIdempotencyKey() {
        issue(webTestClient.mutateWith(admin(ADMIN)), BODY, null)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("INVALID_IDEMPOTENCY_KEY");
        verifyNoInteractions(issueComplimentaryTicketsUseCase);
    }

    @Test
    void shouldRejectMalformedEventIds() {
        webTestClient.mutateWith(admin(ADMIN)).post()
                .uri("/api/v1/events/{eventId}/complimentary-tickets", "a".repeat(65))
                .header(OrderRestController.IDEMPOTENCY_KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(BODY)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_ERROR");
        verifyNoInteractions(issueComplimentaryTicketsUseCase);
    }

    @Test
    void shouldRejectEventsNotOnSale() {
        given(issueComplimentaryTicketsUseCase.execute(any()))
                .willReturn(Mono.error(new EventNotOnSaleException("e-1", EventStatusEnum.PUBLISHING)));

        issue(webTestClient.mutateWith(admin(ADMIN)), BODY, KEY)
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("EVENT_NOT_ON_SALE");
    }

    @Test
    void shouldRejectReusedIdempotencyKeys() {
        given(issueComplimentaryTicketsUseCase.execute(any()))
                .willReturn(Mono.error(new IdempotencyKeyConflictException(KEY)));

        issue(webTestClient.mutateWith(admin(ADMIN)), BODY, KEY)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }
}
