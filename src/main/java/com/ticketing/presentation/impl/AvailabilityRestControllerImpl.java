package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.GetEventAvailabilityUseCase;
import com.ticketing.business.usecase.StreamEventAvailabilityUseCase;
import com.ticketing.domain.data.AvailabilitySnapshot;
import com.ticketing.presentation.AvailabilityRestController;
import com.ticketing.presentation.dto.AvailabilityResponseDto;
import com.ticketing.presentation.impl.support.ResourceIdValidator;
import com.ticketing.presentation.mapper.AvailabilityDtoMapper;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Availability endpoints: a strongly consistent read and a server-sent-event stream that emits only when the counters
 * change.
 *
 * <p>The stream endpoint is public, so its cost must not grow with the audience: every viewer of an event shares one
 * upstream poll of {@link StreamEventAvailabilityUseCase} (one consistent read per interval on the event item, however
 * many connections are open). The shared stream replays the latest snapshot to a viewer that joins later, stops
 * polling as soon as its last viewer disconnects and is dropped when it terminates. Each connection additionally
 * receives a comment-only heartbeat while the counters do not change (so idle-timeout proxies such as a load balancer
 * keep it open) and is closed after {@link #MAX_STREAM_LIFETIME} ({@code EventSource} clients reconnect
 * transparently).
 */
@RestController
public class AvailabilityRestControllerImpl implements AvailabilityRestController {

    /** Interval of the comment-only keep-alive frames (well below the 60 s default idle timeout of an ALB). */
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    /** Comment carried by the keep-alive frames. */
    static final String HEARTBEAT_COMMENT = "keepalive";

    /** Maximum lifetime of one stream connection. */
    static final Duration MAX_STREAM_LIFETIME = Duration.ofMinutes(30);

    private final GetEventAvailabilityUseCase getEventAvailabilityUseCase;
    private final StreamEventAvailabilityUseCase streamEventAvailabilityUseCase;
    private final AvailabilityDtoMapper mapper;

    /** One shared upstream per event currently streamed; an entry lives while its upstream is subscribed. */
    private final ConcurrentMap<String, Flux<ServerSentEvent<AvailabilityResponseDto>>> sharedStreams =
            new ConcurrentHashMap<>();

    /**
     * Creates the controller.
     *
     * @param getEventAvailabilityUseCase    reads the counters once
     * @param streamEventAvailabilityUseCase streams counter changes
     * @param mapper                         DTO mapper
     */
    public AvailabilityRestControllerImpl(GetEventAvailabilityUseCase getEventAvailabilityUseCase,
                                          StreamEventAvailabilityUseCase streamEventAvailabilityUseCase,
                                          AvailabilityDtoMapper mapper) {
        this.getEventAvailabilityUseCase = Objects.requireNonNull(getEventAvailabilityUseCase,
                "getEventAvailabilityUseCase");
        this.streamEventAvailabilityUseCase = Objects.requireNonNull(streamEventAvailabilityUseCase,
                "streamEventAvailabilityUseCase");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Mono<AvailabilityResponseDto> getAvailability(String eventId) {
        return Mono.fromCallable(() -> ResourceIdValidator.requireValid(ResourceIdValidator.EVENT_ID, eventId))
                .flatMap(getEventAvailabilityUseCase::execute)
                .map(mapper::toResponse);
    }

    @Override
    public Flux<ServerSentEvent<AvailabilityResponseDto>> streamAvailability(String eventId) {
        return Flux.defer(() -> sharedStreams.computeIfAbsent(
                        ResourceIdValidator.requireValid(ResourceIdValidator.EVENT_ID, eventId), this::shareUpdates))
                // The heartbeat ends with the updates (completion or error); errors are reported by the updates only.
                .publish(updates -> Flux.merge(updates,
                        heartbeats().takeUntilOther(updates.then().onErrorComplete())))
                .take(MAX_STREAM_LIFETIME);
    }

    /**
     * Builds the shared upstream of an event: the use-case stream mapped to SSE frames, replaying the latest frame to
     * late subscribers, connected while at least one viewer is subscribed and removed from {@link #sharedStreams}
     * when it is cancelled or terminates (the next viewer then starts a fresh one).
     */
    private Flux<ServerSentEvent<AvailabilityResponseDto>> shareUpdates(String eventId) {
        AtomicReference<Flux<ServerSentEvent<AvailabilityResponseDto>>> self = new AtomicReference<>();
        Flux<ServerSentEvent<AvailabilityResponseDto>> shared =
                Flux.defer(() -> streamEventAvailabilityUseCase.execute(eventId))
                        .map(this::toServerSentEvent)
                        .doFinally(_ -> sharedStreams.remove(eventId, self.get()))
                        .replay(1)
                        .refCount();
        self.set(shared);
        return shared;
    }

    private static Flux<ServerSentEvent<AvailabilityResponseDto>> heartbeats() {
        return Flux.interval(HEARTBEAT_INTERVAL)
                .map(_ -> ServerSentEvent.<AvailabilityResponseDto>builder().comment(HEARTBEAT_COMMENT).build());
    }

    /**
     * Wraps a snapshot as an SSE event named {@value AvailabilityRestController#AVAILABILITY_EVENT_NAME} whose id is
     * the capture instant in epoch milliseconds (monotonic, usable as {@code Last-Event-ID}).
     */
    private ServerSentEvent<AvailabilityResponseDto> toServerSentEvent(AvailabilitySnapshot snapshot) {
        return ServerSentEvent.builder(mapper.toResponse(snapshot))
                .event(AVAILABILITY_EVENT_NAME)
                .id(Long.toString(snapshot.capturedAt().toEpochMilli()))
                .build();
    }
}
