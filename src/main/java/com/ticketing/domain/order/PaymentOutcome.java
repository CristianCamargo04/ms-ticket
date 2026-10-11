package com.ticketing.domain.order;

import com.ticketing.domain.validation.DomainPreconditions;

/**
 * Result of a payment gateway call. Gateway unavailability is not an outcome but an error signal
 * ({@code PaymentGatewayException}).
 */
public sealed interface PaymentOutcome
        permits PaymentOutcome.Approved, PaymentOutcome.Declined, PaymentOutcome.NotFound {

    /**
     * The charge was captured.
     *
     * @param paymentReference gateway payment identifier, non-blank
     */
    record Approved(String paymentReference) implements PaymentOutcome {

        /**
         * Validates the payment reference.
         */
        public Approved {
            DomainPreconditions.requireNonBlank(paymentReference, "paymentReference");
        }
    }

    /**
     * The charge was refused.
     *
     * @param reason gateway decline reason, nullable (for logging only, never exposed to clients)
     */
    record Declined(String reason) implements PaymentOutcome {
    }

    /**
     * No charge exists for the queried idempotency key (only returned by {@code findCharge}).
     */
    record NotFound() implements PaymentOutcome {
    }
}
