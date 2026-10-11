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

import com.ticketing.business.usecase.CreateEventUseCase;
import com.ticketing.business.usecase.GetEventUseCase;
import com.ticketing.business.usecase.ListEventsUseCase;
import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.PageData;
import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.domain.exception.EventNotFoundException;
import com.ticketing.domain.internal_request.CreateEventRequest;
import com.ticketing.domain.internal_request.ListEventsRequest;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.presentation.context.RequestContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(controllers = EventRestControllerImpl.class)
@Import(RestControllerTestConfig.class)
class EventRestControllerImplTest {

    private static final String EVENTS = "/api/v1/events";
    private static final Instant EVENT_DATE = Instant.parse("2099-01-01T20:00:00Z");
    private static final String VALID_BODY = """
            {"name":"Concert","venue":"Arena","eventDate":"2099-01-01T20:00:00Z","totalCapacity":100,
             "ticketPrice":49.90,"currency":"USD"}
            """;

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private CreateEventUseCase createEventUseCase;

    @MockitoBean
    private ListEventsUseCase listEventsUseCase;

    @MockitoBean
    private GetEventUseCase getEventUseCase;

    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    private static EventData event(String eventId) {
        return new EventData(eventId, "Concert", "Arena", EVENT_DATE, 100, new BigDecimal("49.90"), "USD",
                EventStatusEnum.ON_SALE, 97, 2, 1, 0, 0, 3, RestControllerTestConfig.NOW,
                RestControllerTestConfig.NOW);
    }

    @Test
    void shouldCreateEventAndReturnLocation() {
        given(createEventUseCase.execute(any())).willReturn(Mono.just(event("e-1")));

        webTestClient.mutateWith(admin(ADMIN)).post().uri(EVENTS)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(VALID_BODY)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().location("/api/v1/events/e-1")
                .expectBody()
                .jsonPath("$.eventId").isEqualTo("e-1")
                .jsonPath("$.status").isEqualTo("ON_SALE")
                .jsonPath("$.availableTickets").isEqualTo(97)
                .jsonPath("$.ticketPrice").isEqualTo(49.90)
                .jsonPath("$.eventDate").isEqualTo("2099-01-01T20:00:00Z");

        ArgumentCaptor<CreateEventRequest> request = ArgumentCaptor.forClass(CreateEventRequest.class);
        verify(createEventUseCase).execute(request.capture());
        assertThat(request.getValue()).isEqualTo(new CreateEventRequest("Concert", "Arena", EVENT_DATE, 100,
                new BigDecimal("49.90"), "USD"));
    }

    @Test
    void shouldAuthenticateRealBearerTokensWithTheRoleConverter() {
        given(reactiveJwtDecoder.decode("token-admin-1")).willReturn(Mono.just(TestJwts.decoded(ADMIN, "admin")));
        given(createEventUseCase.execute(any())).willReturn(Mono.just(event("e-2")));

        webTestClient.post().uri(EVENTS)
                .headers(headers -> headers.setBearerAuth("token-admin-1"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(VALID_BODY)
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void shouldRejectBearerTokenWithoutAdminRole() {
        given(reactiveJwtDecoder.decode("token-customer-1"))
                .willReturn(Mono.just(TestJwts.decoded(CUSTOMER, "customer")));

        webTestClient.post().uri(EVENTS)
                .headers(headers -> headers.setBearerAuth("token-customer-1"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(VALID_BODY)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("FORBIDDEN");
        verifyNoInteractions(createEventUseCase);
    }

    @Test
    void shouldRejectInvalidBearerTokenWithInvalidTokenChallenge() {
        given(reactiveJwtDecoder.decode("forged")).willReturn(Mono.error(new BadJwtException("bad signature")));

        webTestClient.post().uri(EVENTS)
                .headers(headers -> headers.setBearerAuth("forged"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(VALID_BODY)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.detail").value(detail -> assertThat(detail.toString()).doesNotContain("signature"));
    }

    @Test
    void shouldRequireAuthenticationToCreateEvents() {
        webTestClient.post().uri(EVENTS)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(VALID_BODY)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.title").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.errorCode").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.instance").isEqualTo(EVENTS)
                .jsonPath("$.timestamp").isEqualTo("2026-10-10T10:00:00Z")
                .jsonPath("$.correlationId").isNotEmpty();
        verifyNoInteractions(createEventUseCase);
    }

    @Test
    void shouldForbidCustomersToCreateEvents() {
        webTestClient.mutateWith(customer(CUSTOMER)).post().uri(EVENTS)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(VALID_BODY)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("FORBIDDEN")
                .jsonPath("$.status").isEqualTo(403);
        verifyNoInteractions(createEventUseCase);
    }

    @Test
    void shouldRejectInvalidBodyWithFieldViolations() {
        webTestClient.mutateWith(admin(ADMIN)).post().uri(EVENTS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name":" ","venue":"Arena","eventDate":"2001-01-01T00:00:00Z","totalCapacity":0,
                         "ticketPrice":1.999,"currency":"usd"}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.errors[*].field").value(fields -> assertThat(fields).asInstanceOf(
                                org.assertj.core.api.InstanceOfAssertFactories.LIST)
                        .containsExactly("currency", "eventDate", "name", "ticketPrice", "totalCapacity"))
                .jsonPath("$.errors[0].message").isNotEmpty();
        verifyNoInteractions(createEventUseCase);
    }

    @Test
    void shouldRejectMalformedJson() {
        webTestClient.mutateWith(admin(ADMIN)).post().uri(EVENTS)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.detail").isEqualTo("The request body is malformed");
    }

    @Test
    void shouldRejectMissingBody() {
        webTestClient.mutateWith(admin(ADMIN)).post().uri(EVENTS)
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void shouldRejectBodiesLargerThanTheCodecLimit() {
        String name = "x".repeat(300 * 1024);

        webTestClient.mutateWith(admin(ADMIN)).post().uri(EVENTS)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"" + name + "\"}")
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.status").isEqualTo(413)
                .jsonPath("$.errorCode").isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void shouldRejectUnsupportedMediaType() {
        webTestClient.mutateWith(admin(ADMIN)).post().uri(EVENTS)
                .contentType(MediaType.TEXT_PLAIN).bodyValue("name=Concert")
                .exchange()
                .expectStatus().isEqualTo(415)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(415)
                .jsonPath("$.errorCode").isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void shouldMapUseCaseValidationErrors() {
        given(createEventUseCase.execute(any()))
                .willReturn(Mono.error(new DomainValidationException("capacity exceeds the maximum")));

        webTestClient.mutateWith(admin(ADMIN)).post().uri(EVENTS)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(VALID_BODY)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.detail").isEqualTo("capacity exceeds the maximum");
    }

    @Test
    void shouldListOnSaleEventsByDefaultWithoutAuthentication() {
        given(listEventsUseCase.execute(new ListEventsRequest(EventStatusEnum.ON_SALE, 20, null)))
                .willReturn(Mono.just(new PageData<>(List.of(event("e-1"), event("e-2")), "next-page")));

        webTestClient.get().uri(EVENTS)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(2)
                .jsonPath("$.items[1].eventId").isEqualTo("e-2")
                .jsonPath("$.nextCursor").isEqualTo("next-page");
    }

    @Test
    void shouldPassListingParameters() {
        given(listEventsUseCase.execute(new ListEventsRequest(EventStatusEnum.CLOSED, 5, "abc")))
                .willReturn(Mono.just(PageData.empty()));

        webTestClient.get().uri(EVENTS + "?status=CLOSED&limit=5&cursor=abc")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(0)
                .jsonPath("$.nextCursor").doesNotExist();
    }

    @Test
    void shouldRejectUnknownStatusFilter() {
        webTestClient.get().uri(EVENTS + "?status=SOLD_OUT")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.detail").isEqualTo("Invalid value for parameter 'status'");
        verifyNoInteractions(listEventsUseCase);
    }

    @Test
    void shouldMapMalformedCursor() {
        given(listEventsUseCase.execute(any()))
                .willReturn(Mono.error(new DomainValidationException("Malformed cursor")));

        webTestClient.get().uri(EVENTS + "?cursor=%%%")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void shouldReadEvent() {
        given(getEventUseCase.execute("e-1")).willReturn(Mono.just(event("e-1")));

        webTestClient.get().uri(EVENTS + "/e-1")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.name").isEqualTo("Concert")
                .jsonPath("$.createdAt").isEqualTo("2026-10-10T10:00:00Z");
    }

    @Test
    void shouldReturnNotFoundProblemWithCorrelationId() {
        given(getEventUseCase.execute("missing")).willReturn(Mono.error(new EventNotFoundException("missing")));

        webTestClient.get().uri(EVENTS + "/missing")
                .header(RequestContext.CORRELATION_ID_HEADER, "corr-123")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals(RequestContext.CORRELATION_ID_HEADER, "corr-123")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("EVENT_NOT_FOUND")
                .jsonPath("$.errorCode").isEqualTo("EVENT_NOT_FOUND")
                .jsonPath("$.correlationId").isEqualTo("corr-123")
                .jsonPath("$.instance").isEqualTo("/api/v1/events/missing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad_id", "e 1", "e.1"})
    void shouldRejectMalformedEventIdsBeforeCallingTheUseCase(String eventId) {
        webTestClient.get().uri(EVENTS + "/{eventId}", eventId)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.detail").isEqualTo(
                        "The path parameter 'eventId' must contain 1 to 64 letters, digits or dashes");
        verifyNoInteractions(getEventUseCase);
    }

    @Test
    void shouldRejectIdsLongerThanADynamoDbKeyAsClientErrors() {
        webTestClient.get().uri(EVENTS + "/{eventId}", "a".repeat(2049))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.detail").isEqualTo(
                        "The path parameter 'eventId' must contain 1 to 64 letters, digits or dashes");
        verifyNoInteractions(getEventUseCase);
    }

    @Test
    void shouldHideUnexpectedErrors() {
        given(getEventUseCase.execute("boom"))
                .willReturn(Mono.error(new IllegalStateException("table ticketing-events unreachable")));

        webTestClient.get().uri(EVENTS + "/boom")
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("INTERNAL_ERROR")
                .jsonPath("$.detail").isEqualTo("An unexpected error occurred")
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent()))
                        .doesNotContain("ticketing-events").doesNotContain("IllegalStateException"));
    }

    @Test
    void shouldReplaceInvalidCorrelationIds() {
        given(getEventUseCase.execute("e-1")).willReturn(Mono.just(event("e-1")));

        webTestClient.get().uri(EVENTS + "/e-1")
                .header(RequestContext.CORRELATION_ID_HEADER, "bad id\r\ninjected")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value(RequestContext.CORRELATION_ID_HEADER,
                        value -> assertThat(value).matches("^[0-9a-f-]{36}$"));
    }

    @Test
    void shouldRenderUnknownRoutesAsProblem() {
        webTestClient.get().uri(EVENTS + "/e-1/unknown")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.errorCode").isEqualTo("RESOURCE_NOT_FOUND")
                .jsonPath("$.detail").isEqualTo("No resource matches the requested path.");
    }

    @Test
    void shouldRenderUnsupportedMethodsAsProblem() {
        webTestClient.mutateWith(admin(ADMIN)).put().uri(EVENTS + "/e-1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().exists(HttpHeaders.ALLOW)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(405);
    }

    @Test
    void shouldWriteSecurityHeaders() {
        given(getEventUseCase.execute("e-1")).willReturn(Mono.just(event("e-1")));

        webTestClient.mutate().baseUrl("https://localhost").build()
                .get().uri(EVENTS + "/e-1")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value("Strict-Transport-Security",
                        value -> assertThat(value).contains("max-age=31536000").contains("includeSubDomains"))
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
                .expectHeader().valueEquals("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
    }
}
