package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.GetEventAvailabilityUseCase;
import com.ticketing.business.usecase.StreamEventAvailabilityUseCase;
import com.ticketing.domain.data.AvailabilitySnapshot;
import com.ticketing.presentation.AvailabilityRestController;
import com.ticketing.presentation.dto.AvailabilityResponseDto;
import com.ticketing.presentation.impl.support.ResourceIdValidator;
import com.ticketing.presentation.mapper.AvailabilityDtoMapper;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class AvailabilityRestControllerImpl implements AvailabilityRestController {
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    static final String HEARTBEAT_COMMENT = "keepalive";
    static final Duration MAX_STREAM_LIFETIME = Duration.ofMinutes(30);

    private final GetEventAvailabilityUseCase getEventAvailabilityUseCase;
    private final StreamEventAvailabilityUseCase streamEventAvailabilityUseCase;
    private final AvailabilityDtoMapper mapper;

    private final ConcurrentMap<String, Flux<ServerSentEvent<AvailabilityResponseDto>>> sharedStreams =
            new ConcurrentHashMap<>();

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

                .publish(updates -> Flux.merge(updates,
                        heartbeats().takeUntilOther(updates.then().onErrorComplete())))
                .take(MAX_STREAM_LIFETIME);
    }

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

    private ServerSentEvent<AvailabilityResponseDto> toServerSentEvent(AvailabilitySnapshot snapshot) {
        return ServerSentEvent.builder(mapper.toResponse(snapshot))
                .event(AVAILABILITY_EVENT_NAME)
                .id(Long.toString(snapshot.capturedAt().toEpochMilli()))
                .build();
    }
}
