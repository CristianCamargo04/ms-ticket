package com.ticketing.presentation.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ticketing.business.usecase.GetEventAvailabilityUseCase;
import com.ticketing.business.usecase.StreamEventAvailabilityUseCase;
import com.ticketing.domain.data.AvailabilitySnapshot;
import com.ticketing.domain.exception.EventNotFoundException;
import com.ticketing.presentation.AvailabilityRestController;
import com.ticketing.presentation.dto.AvailabilityResponseDto;
import com.ticketing.presentation.mapper.AvailabilityDtoMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
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

    /** A controller outside the web layer, for subscription-level assertions on the stream. */
    private AvailabilityRestControllerImpl controller() {
        return new AvailabilityRestControllerImpl(getEventAvailabilityUseCase, streamEventAvailabilityUseCase,
                new AvailabilityDtoMapper());
    }

    private static Integer availableOf(ServerSentEvent<AvailabilityResponseDto> event) {
        return event.data() == null ? null : event.data().available();
    }

    private static boolean isHeartbeat(ServerSentEvent<AvailabilityResponseDto> event) {
        return AvailabilityRestControllerImpl.HEARTBEAT_COMMENT.equals(event.comment()) && event.data() == null
                && event.event() == null;
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

    @ParameterizedTest
    @ValueSource(strings = {"bad_id", "e 1"})
    void shouldRejectMalformedEventIds(String eventId) {
        webTestClient.get().uri("/api/v1/events/{eventId}/availability", eventId)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_ERROR");
        webTestClient.get().uri("/api/v1/events/{eventId}/availability/stream", eventId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("VALIDATION_ERROR");
        verifyNoInteractions(getEventAvailabilityUseCase, streamEventAvailabilityUseCase);
    }

    @Test
    void shouldShareOnePollerBetweenAllViewersOfAnEventAndReplayTheLatestSnapshot() {
        Sinks.Many<AvailabilitySnapshot> snapshots = Sinks.many().multicast().directBestEffort();
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        given(streamEventAvailabilityUseCase.execute("e-1")).willReturn(snapshots.asFlux()
                .doOnSubscribe(_ -> subscriptions.incrementAndGet())
                .doOnCancel(() -> cancelled.set(true)));
        AvailabilityRestControllerImpl controller = controller();
        List<Integer> first = new CopyOnWriteArrayList<>();
        List<Integer> second = new CopyOnWriteArrayList<>();
        List<Integer> late = new CopyOnWriteArrayList<>();

        Disposable firstViewer = controller.streamAvailability("e-1").mapNotNull(AvailabilityRestControllerImplTest::availableOf)
                .subscribe(first::add);
        Disposable secondViewer = controller.streamAvailability("e-1").mapNotNull(AvailabilityRestControllerImplTest::availableOf)
                .subscribe(second::add);
        snapshots.tryEmitNext(snapshot(90, FIRST));
        Disposable lateViewer = controller.streamAvailability("e-1").mapNotNull(AvailabilityRestControllerImplTest::availableOf)
                .subscribe(late::add);
        snapshots.tryEmitNext(snapshot(88, SECOND));

        assertThat(subscriptions).hasValue(1);
        verify(streamEventAvailabilityUseCase, times(1)).execute("e-1");
        assertThat(first).containsExactly(90, 88);
        assertThat(second).containsExactly(90, 88);
        assertThat(late).containsExactly(90, 88);

        firstViewer.dispose();
        secondViewer.dispose();
        assertThat(cancelled).isFalse();
        lateViewer.dispose();
        assertThat(cancelled).isTrue();

        Disposable nextViewer = controller.streamAvailability("e-1").subscribe();
        assertThat(subscriptions).hasValue(2);
        nextViewer.dispose();
    }

    @Test
    void shouldKeepSeparatePollersPerEvent() {
        given(streamEventAvailabilityUseCase.execute("e-1")).willReturn(Flux.never());
        given(streamEventAvailabilityUseCase.execute("e-2")).willReturn(Flux.never());
        AvailabilityRestControllerImpl controller = controller();

        Disposable first = controller.streamAvailability("e-1").subscribe();
        Disposable second = controller.streamAvailability("e-2").subscribe();

        verify(streamEventAvailabilityUseCase).execute("e-1");
        verify(streamEventAvailabilityUseCase).execute("e-2");
        first.dispose();
        second.dispose();
    }

    @Test
    void shouldStartAFreshPollerAfterTheSharedOneFailed() {
        given(streamEventAvailabilityUseCase.execute("e-1"))
                .willReturn(Flux.error(new EventNotFoundException("e-1")))
                .willReturn(Flux.just(snapshot(90, FIRST)));
        AvailabilityRestControllerImpl controller = controller();

        StepVerifier.create(controller.streamAvailability("e-1"))
                .verifyError(EventNotFoundException.class);
        StepVerifier.create(controller.streamAvailability("e-1"))
                .assertNext(event -> assertThat(availableOf(event)).isEqualTo(90))
                .verifyComplete();
        verify(streamEventAvailabilityUseCase, times(2)).execute("e-1");
    }

    @Test
    void shouldReportErrorsRaisedAfterTheFirstSnapshot() {
        given(streamEventAvailabilityUseCase.execute("e-1"))
                .willReturn(Flux.concat(Flux.just(snapshot(90, FIRST)),
                        Flux.error(new IllegalStateException("throttled"))));

        StepVerifier.create(controller().streamAvailability("e-1"))
                .assertNext(event -> assertThat(availableOf(event)).isEqualTo(90))
                .verifyErrorMessage("throttled");
    }

    @Test
    void shouldSendHeartbeatsWhileTheCountersDoNotChangeAndCloseLongLivedStreams() {
        given(streamEventAvailabilityUseCase.execute("e-1"))
                .willReturn(Flux.concat(Flux.just(snapshot(90, FIRST)), Flux.never()));

        StepVerifier.withVirtualTime(() -> controller().streamAvailability("e-1"))
                .assertNext(event -> assertThat(availableOf(event)).isEqualTo(90))
                .expectNoEvent(AvailabilityRestControllerImpl.HEARTBEAT_INTERVAL.minusMillis(1))
                .thenAwait(Duration.ofMillis(1))
                .assertNext(event -> assertThat(isHeartbeat(event)).isTrue())
                .thenAwait(AvailabilityRestControllerImpl.MAX_STREAM_LIFETIME)
                .thenConsumeWhile(AvailabilityRestControllerImplTest::isHeartbeat)
                .verifyComplete();
    }
}
