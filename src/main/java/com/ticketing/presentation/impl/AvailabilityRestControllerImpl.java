package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.GetEventAvailabilityUseCase;
import com.ticketing.business.usecase.StreamEventAvailabilityUseCase;
import com.ticketing.domain.data.AvailabilitySnapshot;
import com.ticketing.presentation.AvailabilityRestController;
import com.ticketing.presentation.dto.AvailabilityResponseDto;
import com.ticketing.presentation.mapper.AvailabilityDtoMapper;
import java.util.Objects;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Availability endpoints: a strongly consistent read and a server-sent-event stream that emits only when the counters
 * change. The stream is cancelled (and the polling stops) when the client disconnects.
 */
@RestController
public class AvailabilityRestControllerImpl implements AvailabilityRestController {

    private final GetEventAvailabilityUseCase getEventAvailabilityUseCase;
    private final StreamEventAvailabilityUseCase streamEventAvailabilityUseCase;
    private final AvailabilityDtoMapper mapper;

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
        return Mono.defer(() -> getEventAvailabilityUseCase.execute(eventId)).map(mapper::toResponse);
    }

    @Override
    public Flux<ServerSentEvent<AvailabilityResponseDto>> streamAvailability(String eventId) {
        return Flux.defer(() -> streamEventAvailabilityUseCase.execute(eventId)).map(this::toServerSentEvent);
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
