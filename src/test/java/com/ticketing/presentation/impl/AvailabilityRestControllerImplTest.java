package com.ticketing.presentation.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.ticketing.business.usecase.GetEventAvailabilityUseCase;
import com.ticketing.business.usecase.StreamEventAvailabilityUseCase;
import com.ticketing.domain.data.AvailabilitySnapshot;
import com.ticketing.domain.exception.EventNotFoundException;
import com.ticketing.presentation.AvailabilityRestController;
import com.ticketing.presentation.dto.AvailabilityResponseDto;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@WebFluxTest(controllers = AvailabilityRestControllerImpl.class)
@Import(RestControllerTestConfig.class)
class AvailabilityRestControllerImplTest {

    private static final Instant FIRST = Instant.parse("2026-10-10T10:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-10-10T10:00:01Z");
    private static final ParameterizedTypeReference<ServerSentEvent<AvailabilityResponseDto>> SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private GetEventAvailabilityUseCase getEventAvailabilityUseCase;

    @MockitoBean
    private StreamEventAvailabilityUseCase streamEventAvailabilityUseCase;

    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    private static AvailabilitySnapshot snapshot(int available, Instant capturedAt) {
        return new AvailabilitySnapshot("e-1", 100, available, 3, 2, 100 - available - 5, 0, capturedAt);
    }

    @Test
    void shouldReadAvailabilityWithoutAuthentication() {
        given(getEventAvailabilityUseCase.execute("e-1")).willReturn(Mono.just(snapshot(90, FIRST)));

        webTestClient.get().uri("/api/v1/events/e-1/availability")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.eventId").isEqualTo("e-1")
                .jsonPath("$.totalCapacity").isEqualTo(100)
                .jsonPath("$.available").isEqualTo(90)
                .jsonPath("$.reserved").isEqualTo(3)
                .jsonPath("$.pendingConfirmation").isEqualTo(2)
                .jsonPath("$.sold").isEqualTo(5)
                .jsonPath("$.complimentary").isEqualTo(0)
                .jsonPath("$.capturedAt").isEqualTo("2026-10-10T10:00:00Z");
    }

    @Test
    void shouldReturnNotFoundForUnknownEvents() {
        given(getEventAvailabilityUseCase.execute("missing"))
                .willReturn(Mono.error(new EventNotFoundException("missing")));

        webTestClient.get().uri("/api/v1/events/missing/availability")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("EVENT_NOT_FOUND");
    }

    @Test
    void shouldStreamAvailabilityAsNamedServerSentEvents() {
        given(streamEventAvailabilityUseCase.execute("e-1"))
                .willReturn(Flux.just(snapshot(90, FIRST), snapshot(88, SECOND)));

        FluxExchangeResult<ServerSentEvent<AvailabilityResponseDto>> result = webTestClient.get()
                .uri("/api/v1/events/e-1/availability/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(SSE_TYPE);

        StepVerifier.create(result.getResponseBody())
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo(AvailabilityRestController.AVAILABILITY_EVENT_NAME);
                    assertThat(event.id()).isEqualTo(Long.toString(FIRST.toEpochMilli()));
                    assertThat(event.data()).isNotNull();
                    assertThat(event.data().available()).isEqualTo(90);
                    assertThat(event.data().capturedAt()).isEqualTo(FIRST);
                })
                .assertNext(event -> {
                    assertThat(event.id()).isEqualTo(Long.toString(SECOND.toEpochMilli()));
                    assertThat(event.data()).isNotNull();
                    assertThat(event.data().available()).isEqualTo(88);
                })
                .verifyComplete();
    }

    @Test
    void shouldStopPollingWhenTheClientDisconnects() {
        AtomicBoolean cancelled = new AtomicBoolean();
        given(streamEventAvailabilityUseCase.execute("e-1"))
                .willReturn(Flux.concat(Flux.just(snapshot(90, FIRST)), Flux.<AvailabilitySnapshot>never())
                        .doOnCancel(() -> cancelled.set(true)));

        FluxExchangeResult<ServerSentEvent<AvailabilityResponseDto>> result = webTestClient.get()
                .uri("/api/v1/events/e-1/availability/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(SSE_TYPE);

        StepVerifier.create(result.getResponseBody())
                .assertNext(event -> assertThat(event.event()).isEqualTo("availability"))
                .thenCancel()
                .verify();
        assertThat(cancelled).isTrue();
    }

    @Test
    void shouldReportUnknownEventsBeforeStreaming() {
        given(streamEventAvailabilityUseCase.execute("missing"))
                .willReturn(Flux.error(new EventNotFoundException("missing")));

        webTestClient.get().uri("/api/v1/events/missing/availability/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("EVENT_NOT_FOUND");
    }
}
