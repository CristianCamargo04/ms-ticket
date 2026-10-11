package com.ticketing.presentation.impl;

import static com.ticketing.presentation.impl.TestJwts.ADMIN;
import static com.ticketing.presentation.impl.TestJwts.CUSTOMER;
import static com.ticketing.presentation.impl.TestJwts.admin;
import static com.ticketing.presentation.impl.TestJwts.customer;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketing.business.usecase.GetTicketHistoryUseCase;
import com.ticketing.domain.data.TicketTransitionData;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

@WebFluxTest(controllers = TicketHistoryRestControllerImpl.class)
@Import(RestControllerTestConfig.class)
class TicketHistoryRestControllerImplTest {

    private static final String URI = "/api/v1/events/e-1/tickets/T-000001/transitions";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private GetTicketHistoryUseCase getTicketHistoryUseCase;

    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    @Test
    void shouldReturnTheAuditTrailInOrder() {
        given(getTicketHistoryUseCase.execute("e-1", "T-000001")).willReturn(Flux.just(
                new TicketTransitionData("e-1", "T-000001", "tr-1", TicketStatusEnum.AVAILABLE,
                        TicketStatusEnum.RESERVED, "o-1", TransitionReasonEnum.RESERVATION, "order-worker",
                        Instant.parse("2026-10-10T10:00:00Z")),
                new TicketTransitionData("e-1", "T-000001", "tr-2", TicketStatusEnum.RESERVED,
                        TicketStatusEnum.AVAILABLE, "o-1", TransitionReasonEnum.HOLD_EXPIRED, "sweeper",
                        Instant.parse("2026-10-10T10:10:00Z"))));

        webTestClient.mutateWith(admin(ADMIN)).get().uri(URI)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].ticketId").isEqualTo("T-000001")
                .jsonPath("$[0].fromStatus").isEqualTo("AVAILABLE")
                .jsonPath("$[0].toStatus").isEqualTo("RESERVED")
                .jsonPath("$[0].reason").isEqualTo("RESERVATION")
                .jsonPath("$[1].reason").isEqualTo("HOLD_EXPIRED")
                .jsonPath("$[1].actor").isEqualTo("sweeper")
                .jsonPath("$[1].occurredAt").isEqualTo("2026-10-10T10:10:00Z");
    }

    @Test
    void shouldReturnAnEmptyArrayWhenTheTicketHasNoTransitions() {
        given(getTicketHistoryUseCase.execute("e-1", "T-000001")).willReturn(Flux.empty());

        webTestClient.mutateWith(admin(ADMIN)).get().uri(URI)
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");
    }

    @ParameterizedTest
    @CsvSource({"bad_event, T-000001, eventId", "e-1, T_000001, ticketId"})
    void shouldRejectMalformedIdentifiers(String eventId, String ticketId, String parameter) {
        webTestClient.mutateWith(admin(ADMIN)).get()
                .uri("/api/v1/events/{eventId}/tickets/{ticketId}/transitions", eventId, ticketId)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.detail").isEqualTo(
                        "The path parameter '" + parameter + "' must contain 1 to 64 letters, digits or dashes");
        verifyNoInteractions(getTicketHistoryUseCase);
    }

    @Test
    void shouldForbidCustomers() {
        webTestClient.mutateWith(customer(CUSTOMER)).get().uri(URI)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("FORBIDDEN");
        verifyNoInteractions(getTicketHistoryUseCase);
    }

    @Test
    void shouldNotBePublicLikeTheOtherEventReads() {
        webTestClient.get().uri(URI)
                .exchange()
                .expectStatus().isUnauthorized();
        verifyNoInteractions(getTicketHistoryUseCase);
    }
}
