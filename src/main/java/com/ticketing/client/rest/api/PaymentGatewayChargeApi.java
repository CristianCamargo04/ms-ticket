package com.ticketing.client.rest.api;

import com.ticketing.domain.external_request.payment.PaymentChargeRequest;
import com.ticketing.domain.order.PaymentOutcome;
import reactor.core.publisher.Mono;

/**
 * Payment gateway charges. Timeouts, 5xx responses and an open circuit are error signals
 * {@code PaymentGatewayException} (retryable).
 */
public interface PaymentGatewayChargeApi {

    /**
     * Charges an order; idempotent on {@link PaymentChargeRequest#idempotencyKey()}.
     *
     * @param request the charge
     * @return {@link PaymentOutcome.Approved} or {@link PaymentOutcome.Declined}
     */
    Mono<PaymentOutcome> charge(PaymentChargeRequest request);

    /**
     * Looks up the result of a previous charge (payment reconciliation).
     *
     * @param idempotencyKey the idempotency key used for the charge
     * @return {@link PaymentOutcome.Approved}, {@link PaymentOutcome.Declined} or {@link PaymentOutcome.NotFound}
     */
    Mono<PaymentOutcome> findCharge(String idempotencyKey);
}
