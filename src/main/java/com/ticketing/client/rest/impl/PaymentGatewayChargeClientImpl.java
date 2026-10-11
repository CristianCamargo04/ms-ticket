package com.ticketing.client.rest.impl;

import com.ticketing.client.dto.payment.ChargeResponseDto;
import com.ticketing.client.mapper.PaymentGatewayChargeClientMapper;
import com.ticketing.client.rest.api.PaymentGatewayChargeApi;
import com.ticketing.domain.exception.PaymentGatewayException;
import com.ticketing.domain.external_request.payment.PaymentChargeRequest;
import com.ticketing.domain.order.PaymentOutcome;
import com.ticketing.util.ReactiveRetryUtil;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.RetryBackoffSpec;

@Slf4j
@Component
public final class PaymentGatewayChargeClientImpl implements PaymentGatewayChargeApi {
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    static final String CHARGES_PATH = "/v1/charges";
    static final String CHARGE_PATH = "/v1/charges/{idempotencyKey}";

    static final double RETRY_JITTER = 0.5;

    static final int MAX_BACKOFF_FACTOR = 10;

    private static final String CHARGE = "charge";
    private static final String LOOKUP = "charge lookup";

    private final WebClient webClient;
    private final PaymentGatewaySettings settings;
    private final CircuitBreaker circuitBreaker;
    private final PaymentGatewayChargeClientMapper mapper;
    private final RetryBackoffSpec retrySpec;

    public PaymentGatewayChargeClientImpl(WebClient paymentGatewayWebClient, PaymentGatewaySettings settings,
                                          CircuitBreaker paymentGatewayCircuitBreaker,
                                          PaymentGatewayChargeClientMapper mapper) {
        this.webClient = Objects.requireNonNull(paymentGatewayWebClient, "paymentGatewayWebClient");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.circuitBreaker = Objects.requireNonNull(paymentGatewayCircuitBreaker, "paymentGatewayCircuitBreaker");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.retrySpec = ReactiveRetryUtil.backoff(settings.maxRetries(), settings.retryBackoff(),
                settings.retryBackoff().multipliedBy(MAX_BACKOFF_FACTOR), RETRY_JITTER,
                PaymentGatewayChargeClientImpl::isTransientFailure);
    }

    @Override
    public Mono<PaymentOutcome> charge(PaymentChargeRequest request) {
        Objects.requireNonNull(request, "request");
        String idempotencyKey = requireIdempotencyKey(request.idempotencyKey());
        Mono<PaymentOutcome> call = webClient.post()
                .uri(CHARGES_PATH)
                .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(mapper.toChargeRequest(request))
                .retrieve()
                .bodyToMono(ChargeResponseDto.class)
                .map(mapper::toOutcome)
                .switchIfEmpty(Mono.error(() -> emptyResponse(CHARGE)));
        return resilient(call, CHARGE, idempotencyKey);
    }

    @Override
    public Mono<PaymentOutcome> findCharge(String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        Mono<PaymentOutcome> call = webClient.get()
                .uri(CHARGE_PATH, idempotencyKey)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(ChargeResponseDto.class)
                .map(mapper::toOutcome)
                .switchIfEmpty(Mono.error(() -> emptyResponse(LOOKUP)))
                .onErrorResume(WebClientResponseException.NotFound.class,
                        _ -> Mono.just(new PaymentOutcome.NotFound()));
        return resilient(call, LOOKUP, idempotencyKey);
    }

    public static boolean isTransientFailure(Throwable error) {
        return switch (error) {
            case WebClientResponseException response -> response.getStatusCode().is5xxServerError();
            case WebClientRequestException _, TimeoutException _ -> true;
            case null, default -> false;
        };
    }

    private static String requireIdempotencyKey(String idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        return idempotencyKey;
    }

    private Mono<PaymentOutcome> resilient(Mono<PaymentOutcome> call, String operation, String idempotencyKey) {
        return call
                .timeout(settings.callTimeout())
                .retryWhen(retrySpec)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(error -> toGatewayException(error, operation, idempotencyKey));
    }

    private static PaymentGatewayException toGatewayException(Throwable error, String operation,
                                                              String idempotencyKey) {
        String reason = switch (error) {
            case CallNotPermittedException _ -> "circuit breaker is open";
            case WebClientResponseException response -> "HTTP " + response.getStatusCode().value();
            case TimeoutException _ -> "timed out";
            case WebClientRequestException _ -> "I/O error";
            case PaymentGatewayException gatewayError -> gatewayError.getMessage();
            default -> "unexpected " + error.getClass().getSimpleName();
        };
        log.warn("Payment gateway {} failed for idempotency key {}: {}", operation, idempotencyKey, reason);
        if (error instanceof PaymentGatewayException gatewayError) {
            return gatewayError;
        }
        return new PaymentGatewayException("Payment gateway " + operation + " failed: " + reason, error);
    }

    private static PaymentGatewayException emptyResponse(String operation) {
        return new PaymentGatewayException("Payment gateway returned an empty " + operation + " response");
    }
}
