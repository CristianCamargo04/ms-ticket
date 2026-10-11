package com.ticketing.presentation;

import com.ticketing.enums.EventStatusEnum;
import com.ticketing.presentation.dto.CreateEventRequestDto;
import com.ticketing.presentation.dto.EventResponseDto;
import com.ticketing.presentation.dto.PageResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Mono;

/**
 * REST contract of events. The implementation ({@code presentation.impl.EventRestControllerImpl}) is a
 * {@code @RestController} implementing this interface; the mapping annotations declared here are inherited.
 */
@RequestMapping("/api/v1/events")
public interface EventRestController {

    /**
     * Creates an event and generates its tickets (ADMIN).
     *
     * @param request the event definition
     * @return 201 with the created event and a {@code Location: /api/v1/events/{eventId}} header
     */
    @PostMapping
    Mono<ResponseEntity<EventResponseDto>> createEvent(@Valid @RequestBody CreateEventRequestDto request);

    /**
     * Lists events by status (public).
     *
     * @param status status filter, {@code ON_SALE} by default
     * @param limit  page size, 20 by default (clamped to 1..100 by the use case)
     * @param cursor opaque cursor of the requested page, absent for the first page
     * @return the page
     */
    @GetMapping
    Mono<PageResponseDto<EventResponseDto>> listEvents(
            @RequestParam(name = "status", defaultValue = "ON_SALE") EventStatusEnum status,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "cursor", required = false) String cursor);

    /**
     * Reads an event (public).
     *
     * @param eventId the event identifier
     * @return the event; 404 {@code EVENT_NOT_FOUND} when missing
     */
    @GetMapping("/{eventId}")
    Mono<EventResponseDto> getEvent(@PathVariable("eventId") String eventId);
}
