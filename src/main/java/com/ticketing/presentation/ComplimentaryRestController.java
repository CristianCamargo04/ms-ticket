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

/**
 * REST contract of complimentary tickets (ADMIN).
 */
@RequestMapping("/api/v1/events/{eventId}/complimentary-tickets")
public interface ComplimentaryRestController {

    /**
     * Accepts a complimentary issue and queues it.
     *
     * @param eventId        the event identifier
     * @param idempotencyKey the required idempotency key ({@code Idempotency-Key} header)
     * @param request        beneficiary and quantity
     * @param httpRequest    the request (security and correlation context)
     * @return 202 with the accepted order and a {@code Location: /api/v1/orders/{orderId}} header
     */
    @PostMapping
    Mono<ResponseEntity<OrderAcceptedResponseDto>> issueComplimentary(
            @PathVariable("eventId") String eventId,
            @RequestHeader(OrderRestController.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody IssueComplimentaryRequestDto request,
            ServerHttpRequest httpRequest);
}
