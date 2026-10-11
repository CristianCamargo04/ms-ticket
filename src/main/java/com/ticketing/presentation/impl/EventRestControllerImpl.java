package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.CreateEventUseCase;
import com.ticketing.business.usecase.GetEventUseCase;
import com.ticketing.business.usecase.ListEventsUseCase;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.presentation.EventRestController;
import com.ticketing.presentation.dto.CreateEventRequestDto;
import com.ticketing.presentation.dto.EventResponseDto;
import com.ticketing.presentation.dto.PageResponseDto;
import com.ticketing.presentation.impl.support.ResourceIdValidator;
import com.ticketing.presentation.mapper.EventDtoMapper;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
@RestController
public class EventRestControllerImpl implements EventRestController {
    static final String EVENT_PATH = "/api/v1/events/{eventId}";

    private final CreateEventUseCase createEventUseCase;
    private final ListEventsUseCase listEventsUseCase;
    private final GetEventUseCase getEventUseCase;
    private final EventDtoMapper mapper;

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
        return Mono.fromCallable(() -> ResourceIdValidator.requireValid(ResourceIdValidator.EVENT_ID, eventId))
                .flatMap(getEventUseCase::execute)
                .map(mapper::toResponse);
    }

    private static URI eventUri(String eventId) {
        return UriComponentsBuilder.fromPath(EVENT_PATH).encode().buildAndExpand(eventId).toUri();
    }
}
