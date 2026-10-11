package com.ticketing.client.rest.api;

import com.ticketing.domain.external_request.payment.PaymentChargeRequest;
import com.ticketing.domain.order.PaymentOutcome;
import reactor.core.publisher.Mono;

public interface PaymentGatewayChargeApi {
    Mono<PaymentOutcome> charge(PaymentChargeRequest request);

    Mono<PaymentOutcome> findCharge(String idempotencyKey);
}
