package com.ticketing.business.usecase.impl.testing;

import com.ticketing.client.sdk.SqsOrderQueueClient;
import com.ticketing.domain.internal_request.OrderReservationMessage;
import com.ticketing.domain.internal_request.PaymentRequestedMessage;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class RecordingSqsOrderQueueClient implements SqsOrderQueueClient {
    private final Queue<OrderReservationMessage> reservations = new ConcurrentLinkedQueue<>();
    private final Queue<PaymentRequestedMessage> payments = new ConcurrentLinkedQueue<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public Mono<String> publishReservationRequested(OrderReservationMessage message) {
        return Mono.fromCallable(() -> {
            reservations.add(message);
            return "msg-" + sequence.incrementAndGet();
        }).subscribeOn(Schedulers.parallel());
    }

    @Override
    public Mono<String> publishPaymentRequested(PaymentRequestedMessage message) {
        return Mono.fromCallable(() -> {
            payments.add(message);
            return "msg-" + sequence.incrementAndGet();
        }).subscribeOn(Schedulers.parallel());
    }

    public List<OrderReservationMessage> reservationMessages() {
        return List.copyOf(reservations);
    }

    public List<PaymentRequestedMessage> paymentMessages() {
        return List.copyOf(payments);
    }
}
