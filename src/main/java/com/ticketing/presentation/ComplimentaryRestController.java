package com.ticketing.presentation;

import com.ticketing.presentation.dto.IssueComplimentaryRequestDto;
import com.ticketing.presentation.dto.OrderAcceptedResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import reactor.core.publisher.Mono;

@RequestMapping("/api/v1/events/{eventId}/complimentary-tickets")
public interface ComplimentaryRestController {
    @PostMapping
    Mono<ResponseEntity<OrderAcceptedResponseDto>> issueComplimentary(
            @PathVariable("eventId") String eventId,
            @RequestHeader(name = OrderRestController.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody IssueComplimentaryRequestDto request,
            ServerHttpRequest httpRequest);
}
