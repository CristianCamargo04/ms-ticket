package com.ticketing.presentation;

import com.ticketing.presentation.dto.AvailabilityResponseDto;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RequestMapping("/api/v1/events/{eventId}/availability")
public interface AvailabilityRestController {
    String AVAILABILITY_EVENT_NAME = "availability";

    @GetMapping
    Mono<AvailabilityResponseDto> getAvailability(@PathVariable("eventId") String eventId);

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<AvailabilityResponseDto>> streamAvailability(@PathVariable("eventId") String eventId);
}
