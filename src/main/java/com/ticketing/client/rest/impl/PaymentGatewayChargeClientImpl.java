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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.RetryBackoffSpec;

/**
 * Payment gateway REST client ({@code WebClient} on Reactor Netty).
 *
 * <p>Every call goes through the same resilience pipeline, from the inside out:
 * <ol>
 *   <li>{@code timeout(callTimeout)} bounds each attempt (connect + response + body);</li>
 *   <li>up to {@code maxRetries} retries with exponential backoff and jitter, <em>only</em> for transient failures
 *   ({@link #isTransientFailure(Throwable)}: 5xx, timeouts, connection errors). Retrying a charge is safe because
 *   the gateway deduplicates on the {@code Idempotency-Key} header (the order id), which is therefore mandatory:
 *   a call without a non-blank key is rejected before any request is sent;</li>
 *   <li>the resilience4j circuit breaker, which sees one call per operation (all its attempts) and fails fast with
 *   {@link CallNotPermittedException} while open;</li>
 *   <li>every remaining failure becomes a {@link PaymentGatewayException} (retryable), logged with the operation
 *   and the idempotency key, never with the payment token. Client errors (4xx) are not retried but are reported
 *   the same way: they must never be swallowed.</li>
 * </ol>
 * The only non-error 4xx is {@code 404} on {@link #findCharge(String)}, which means the charge does not exist
 * ({@link PaymentOutcome.NotFound}).
 */
@Component
public final class PaymentGatewayChargeClientImpl implements PaymentGatewayChargeApi {

    /** Header carrying the idempotency key of a charge. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    static final String CHARGES_PATH = "/v1/charges";
    static final String CHARGE_PATH = "/v1/charges/{idempotencyKey}";

    /** Jitter factor of the retry backoff. */
    static final double RETRY_JITTER = 0.5;

    /** The retry backoff grows exponentially up to this multiple of the first backoff. */
    static final int MAX_BACKOFF_FACTOR = 10;

    private static final String CHARGE = "charge";
    private static final String LOOKUP = "charge lookup";

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayChargeClientImpl.class);

    private final WebClient webClient;
    private final PaymentGatewaySettings settings;
    private final CircuitBreaker circuitBreaker;
    private final PaymentGatewayChargeClientMapper mapper;
    private final RetryBackoffSpec retrySpec;

    /**
     * Creates the client.
     *
     * @param paymentGatewayWebClient      web client bound to the gateway base URL, with connect/response timeouts
     * @param settings                     per-call timeout and retry settings
     * @param paymentGatewayCircuitBreaker circuit breaker protecting the gateway
     * @param mapper                       domain to wire mapper
     */
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

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException     if the request or its idempotency key is {@code null}
     * @throws IllegalArgumentException if the idempotency key is blank: an unkeyed charge would be retried without
     *                                  deduplication and could capture the payment several times, so it never
     *                                  reaches the gateway
     */
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

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException     if the idempotency key is {@code null}
     * @throws IllegalArgumentException if the idempotency key is blank
     */
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

    /**
     * Whether a failure is transient, i.e. worth a retry and counted as a gateway failure by the circuit breaker:
     * a 5xx response, a timeout ({@code timeout} operator or Reactor Netty response timeout) or an I/O error before
     * a response was received (connection refused or reset, connect timeout). Client errors (4xx), unreadable
     * responses and unknown statuses are not transient: the gateway answered.
     *
     * @param error any failure
     * @return {@code true} for transient failures
     */
    public static boolean isTransientFailure(Throwable error) {
        return switch (error) {
            case WebClientResponseException response -> response.getStatusCode().is5xxServerError();
            case WebClientRequestException _, TimeoutException _ -> true;
            case null, default -> false;
        };
    }

    /**
     * Precondition of every gateway call: the idempotency key is what makes a retried charge safe, so a missing or
     * blank key is a programming error (spec section 11, item 10) rejected before any request is built.
     */
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
