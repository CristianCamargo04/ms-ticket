package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.IssueComplimentaryTicketsUseCase;
import com.ticketing.presentation.ComplimentaryRestController;
import com.ticketing.presentation.context.RequestContextResolver;
import com.ticketing.presentation.dto.IssueComplimentaryRequestDto;
import com.ticketing.presentation.dto.OrderAcceptedResponseDto;
import com.ticketing.presentation.impl.idempotency.IdempotencyKeyResolver;
import com.ticketing.presentation.impl.idempotency.RequestFingerprintCalculator;
import com.ticketing.presentation.impl.support.ResourceIdValidator;
import com.ticketing.presentation.mapper.OrderDtoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class ComplimentaryRestControllerImpl implements ComplimentaryRestController {
    private final IssueComplimentaryTicketsUseCase issueComplimentaryTicketsUseCase;
    private final RequestContextResolver requestContextResolver;
    private final IdempotencyKeyResolver idempotencyKeyResolver;
    private final RequestFingerprintCalculator fingerprintCalculator;
    private final OrderDtoMapper mapper;

    @Override
    public Mono<ResponseEntity<OrderAcceptedResponseDto>> issueComplimentary(String eventId, String idempotencyKey,
                                                                             IssueComplimentaryRequestDto request,
                                                                             ServerHttpRequest httpRequest) {
        return Mono.fromCallable(() -> {
                    ResourceIdValidator.requireValid(ResourceIdValidator.EVENT_ID, eventId);
                    return idempotencyKeyResolver.resolve(idempotencyKey);
                })
                .flatMap(key -> requestContextResolver.resolve(httpRequest)
                        .flatMap(context -> issueComplimentaryTicketsUseCase.execute(
                                mapper.toIssueComplimentaryRequest(eventId, request, context, key,
                                        fingerprintCalculator.issueComplimentary(eventId, request.beneficiaryId(),
                                                request.quantity())))))
                .map(order -> ResponseEntity.accepted()
                        .location(mapper.statusUri(order.orderId()))
                        .body(mapper.toAcceptedResponse(order)));
    }
}
