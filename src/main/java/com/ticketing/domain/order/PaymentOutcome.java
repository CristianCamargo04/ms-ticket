package com.ticketing.domain.order;

import com.ticketing.domain.validation.DomainPreconditions;

public sealed interface PaymentOutcome
        permits PaymentOutcome.Approved, PaymentOutcome.Declined, PaymentOutcome.NotFound {
    record Approved(String paymentReference) implements PaymentOutcome {
        public Approved {
            DomainPreconditions.requireNonBlank(paymentReference, "paymentReference");
        }
    }

    record Declined(String reason) implements PaymentOutcome {
    }

    record NotFound() implements PaymentOutcome {
    }
}
