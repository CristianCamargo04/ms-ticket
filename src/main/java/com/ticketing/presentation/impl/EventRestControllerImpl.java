package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.CreateEventUseCase;
import com.ticketing.business.usecase.GetEventUseCase;
import com.ticketing.business.usecase.ListEventsUseCase;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.presentation.EventRestController;
import com.ticketing.presentation.dto.CreateEventRequestDto;
import com.ticketing.presentation.dto.EventResponseDto;
import com.ticketing.presentation.dto.PageResponseDto;
import com.ticketing.presentation.mapper.EventDtoMapper;
import java.net.URI;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * Events endpoints; the mappings, {@code @Valid} and binding annotations are inherited from
 * {@link EventRestController}.
 */
@RestController
public class EventRestControllerImpl implements EventRestController {

    /** Path template of the event resource ({@code Location} of a created event). */
    static final String EVENT_PATH = "/api/v1/events/{eventId}";

    private final CreateEventUseCase createEventUseCase;
    private final ListEventsUseCase listEventsUseCase;
    private final GetEventUseCase getEventUseCase;
    private final EventDtoMapper mapper;

    /**
     * Creates the controller.
     *
     * @param createEventUseCase creates events
     * @param listEventsUseCase  lists events
     * @param getEventUseCase    reads one event
     * @param mapper             DTO mapper
     */
    public EventRestControllerImpl(CreateEventUseCase createEventUseCase, ListEventsUseCase listEventsUseCase,
                                   GetEventUseCase getEventUseCase, EventDtoMapper mapper) {
        this.createEventUseCase = Objects.requireNonNull(createEventUseCase, "createEventUseCase");
        this.listEventsUseCase = Objects.requireNonNull(listEventsUseCase, "listEventsUseCase");
        this.getEventUseCase = Objects.requireNonNull(getEventUseCase, "getEventUseCase");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Mono<ResponseEntity<EventResponseDto>> createEvent(CreateEventRequestDto request) {
        return Mono.defer(() -> createEventUseCase.execute(mapper.toCreateEventRequest(request)))
                .map(event -> ResponseEntity.created(eventUri(event.eventId())).body(mapper.toResponse(event)));
    }

    @Override
    public Mono<PageResponseDto<EventResponseDto>> listEvents(EventStatusEnum status, int limit, String cursor) {
        return Mono.defer(() -> listEventsUseCase.execute(mapper.toListEventsRequest(status, limit, cursor)))
                .map(mapper::toPageResponse);
    }

    @Override
    public Mono<EventResponseDto> getEvent(String eventId) {
        return Mono.defer(() -> getEventUseCase.execute(eventId)).map(mapper::toResponse);
    }

    private static URI eventUri(String eventId) {
        return UriComponentsBuilder.fromPath(EVENT_PATH).encode().buildAndExpand(eventId).toUri();
    }
}
