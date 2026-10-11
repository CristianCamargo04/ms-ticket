package com.ticketing.persistence.impl.support;

import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactionConflictException;
import software.amazon.awssdk.services.dynamodb.model.TransactionInProgressException;

/**
 * Translates the failure of an inventory {@code TransactWriteItems} into an {@link InventoryWriteOutcome} or a
 * retryable {@link InventoryConflictException} (implementation spec 6.3).
 *
 * <p>The transaction items have a fixed layout for {@code n} tickets: {@code [0]} event, {@code [1..n]} tickets,
 * {@code [n+1]} order, {@code [n+2..2n+1]} audit records, so the cancellation reasons (one per item, same order) are
 * mapped by index with this priority:
 * <ol>
 *     <li>order {@code ConditionalCheckFailed} → {@link InventoryWriteOutcome.OrderStateChanged}</li>
 *     <li>event {@code ConditionalCheckFailed} → {@link InventoryWriteOutcome.InsufficientInventory}</li>
 *     <li>any ticket {@code ConditionalCheckFailed} → {@link InventoryWriteOutcome.StaleTicketSelection}</li>
 *     <li>{@code TransactionConflict}, throttling, {@code InternalServerError}, an audit collision or no reason at
 *     all → error {@link InventoryConflictException} (retryable; every item is conditional, so a retry is safe)</li>
 * </ol>
 * Outside cancellations, transient failures (transaction in progress/conflict, throttling, 5xx, client-side I/O and
 * timeouts) become {@link InventoryConflictException}; anything else (validation errors, bugs) propagates unchanged.
 */
public final class TransactionCancellationTranslator {

    /** Cancellation code of a failed condition expression. */
    public static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

    private static final Set<String> TRANSIENT_CANCELLATION_CODES = Set.of(
            "TransactionConflict", "ThrottlingError", "ProvisionedThroughputExceeded", "RequestLimitExceeded",
            "InternalServerError", CONDITIONAL_CHECK_FAILED);

    private TransactionCancellationTranslator() {
    }

    /**
     * Translates a transaction failure.
     *
     * @param error       the failure signalled by the SDK (possibly wrapped in a {@link CompletionException})
     * @param ticketCount number of tickets {@code n} of the transaction
     * @param description operation and order, used in error messages (no sensitive data)
     * @return the business outcome, or an error signal
     */
    public static Mono<InventoryWriteOutcome> translate(Throwable error, int ticketCount, String description) {
        Throwable cause = unwrap(error);
        if (cause instanceof TransactionCanceledException canceled) {
            return translateCancellation(canceled, ticketCount, description);
        }
        if (isTransient(cause)) {
            return Mono.error(new InventoryConflictException("Transient DynamoDB failure on " + description, cause));
        }
        return Mono.error(cause);
    }

    /**
     * Whether a non-transactional failure is transient and therefore safe to retry.
     *
     * @param error the unwrapped failure
     * @return {@code true} for transaction conflicts, throttling, 5xx and client-side I/O failures or timeouts
     */
    public static boolean isTransient(Throwable error) {
        if (error instanceof TransactionInProgressException || error instanceof TransactionConflictException
                || error instanceof ProvisionedThroughputExceededException
                || error instanceof RequestLimitExceededException) {
            return true;
        }
        if (error instanceof AwsServiceException serviceException) {
            return serviceException.isThrottlingException() || serviceException.statusCode() >= 500;
        }
        return error instanceof SdkClientException;
    }

    private static Mono<InventoryWriteOutcome> translateCancellation(TransactionCanceledException canceled,
                                                                     int ticketCount, String description) {
        List<String> codes = canceled.hasCancellationReasons()
                ? canceled.cancellationReasons().stream().map(CancellationReason::code).toList()
                : List.of();
        if (conditionFailed(codes, ticketCount + 1)) {
            return Mono.just(new InventoryWriteOutcome.OrderStateChanged());
        }
        if (conditionFailed(codes, 0)) {
            return Mono.just(new InventoryWriteOutcome.InsufficientInventory());
        }
        if (IntStream.rangeClosed(1, ticketCount).anyMatch(index -> conditionFailed(codes, index))) {
            return Mono.just(new InventoryWriteOutcome.StaleTicketSelection());
        }
        if (codes.isEmpty() || codes.stream().anyMatch(TRANSIENT_CANCELLATION_CODES::contains)) {
            return Mono.error(new InventoryConflictException(
                    "Inventory transaction cancelled on " + description + " " + codes, canceled));
        }
        return Mono.error(canceled);
    }

    private static boolean conditionFailed(List<String> codes, int index) {
        return index < codes.size() && CONDITIONAL_CHECK_FAILED.equals(codes.get(index));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
