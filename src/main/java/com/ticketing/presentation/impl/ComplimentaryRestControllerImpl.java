package com.ticketing.presentation.impl;

import com.ticketing.business.usecase.IssueComplimentaryTicketsUseCase;
import com.ticketing.presentation.ComplimentaryRestController;
import com.ticketing.presentation.context.RequestContextResolver;
import com.ticketing.presentation.dto.IssueComplimentaryRequestDto;
import com.ticketing.presentation.dto.OrderAcceptedResponseDto;
import com.ticketing.presentation.impl.idempotency.IdempotencyKeyResolver;
import com.ticketing.presentation.impl.idempotency.RequestFingerprintCalculator;
import com.ticketing.presentation.mapper.OrderDtoMapper;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Complimentary tickets endpoint (ADMIN only). The issuer is the JWT subject of the caller; the beneficiary comes from
 * the body.
 */
@RestController
public class ComplimentaryRestControllerImpl implements ComplimentaryRestController {

    private final IssueComplimentaryTicketsUseCase issueComplimentaryTicketsUseCase;
    private final RequestContextResolver requestContextResolver;
    private final IdempotencyKeyResolver idempotencyKeyResolver;
    private final RequestFingerprintCalculator fingerprintCalculator;
    private final OrderDtoMapper mapper;

    /**
     * Creates the controller.
     *
     * @param issueComplimentaryTicketsUseCase queues complimentary orders
     * @param requestContextResolver           resolves the caller
     * @param idempotencyKeyResolver           validates the idempotency key header
     * @param fingerprintCalculator            fingerprints idempotent requests
     * @param mapper                           DTO mapper
     */
    public ComplimentaryRestControllerImpl(IssueComplimentaryTicketsUseCase issueComplimentaryTicketsUseCase,
                                           RequestContextResolver requestContextResolver,
                                           IdempotencyKeyResolver idempotencyKeyResolver,
                                           RequestFingerprintCalculator fingerprintCalculator,
                                           OrderDtoMapper mapper) {
        this.issueComplimentaryTicketsUseCase = Objects.requireNonNull(issueComplimentaryTicketsUseCase,
                "issueComplimentaryTicketsUseCase");
        this.requestContextResolver = Objects.requireNonNull(requestContextResolver, "requestContextResolver");
        this.idempotencyKeyResolver = Objects.requireNonNull(idempotencyKeyResolver, "idempotencyKeyResolver");
        this.fingerprintCalculator = Objects.requireNonNull(fingerprintCalculator, "fingerprintCalculator");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Mono<ResponseEntity<OrderAcceptedResponseDto>> issueComplimentary(String eventId, String idempotencyKey,
                                                                             IssueComplimentaryRequestDto request,
                                                                             ServerHttpRequest httpRequest) {
        return Mono.fromCallable(() -> idempotencyKeyResolver.resolve(idempotencyKey))
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
