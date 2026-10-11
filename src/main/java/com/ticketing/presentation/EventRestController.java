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

@RequestMapping("/api/v1/events")
public interface EventRestController {
    @PostMapping
    Mono<ResponseEntity<EventResponseDto>> createEvent(@Valid @RequestBody CreateEventRequestDto request);

    @GetMapping
    Mono<PageResponseDto<EventResponseDto>> listEvents(
            @RequestParam(name = "status", defaultValue = "ON_SALE") EventStatusEnum status,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "cursor", required = false) String cursor);

    @GetMapping("/{eventId}")
    Mono<EventResponseDto> getEvent(@PathVariable("eventId") String eventId);
}
