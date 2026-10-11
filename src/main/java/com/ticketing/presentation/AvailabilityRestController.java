package com.ticketing.presentation;

import com.ticketing.presentation.dto.AvailabilityResponseDto;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST contract of event availability (public).
 */
@RequestMapping("/api/v1/events/{eventId}/availability")
public interface AvailabilityRestController {

    /** Name of the server-sent events emitted by {@link #streamAvailability(String)}. */
    String AVAILABILITY_EVENT_NAME = "availability";

    /**
     * Reads the current counters of an event (strongly consistent).
     *
     * @param eventId the event identifier
     * @return the counters; 404 {@code EVENT_NOT_FOUND} when missing
     */
    @GetMapping
    Mono<AvailabilityResponseDto> getAvailability(@PathVariable("eventId") String eventId);

    /**
     * Streams the counters of an event as server-sent events named {@value #AVAILABILITY_EVENT_NAME}, emitted only
     * when they change; the stream ends when the client disconnects.
     *
     * @param eventId the event identifier
     * @return the event stream
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<AvailabilityResponseDto>> streamAvailability(@PathVariable("eventId") String eventId);
}
