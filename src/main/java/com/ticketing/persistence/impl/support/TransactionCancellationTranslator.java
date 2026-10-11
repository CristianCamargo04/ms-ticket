package com.ticketing.persistence.impl.support;

import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.NonRetryableException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactionConflictException;
import software.amazon.awssdk.services.dynamodb.model.TransactionInProgressException;

public final class TransactionCancellationTranslator {
    public static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

    private static final String NO_FAILURE = "None";

    private static final int MAX_CAUSE_DEPTH = 16;

    private static final Set<String> TRANSIENT_CANCELLATION_CODES = Set.of(
            "TransactionConflict", "ThrottlingError", "ProvisionedThroughputExceeded", "RequestLimitExceeded",
            "InternalServerError", CONDITIONAL_CHECK_FAILED);

    private TransactionCancellationTranslator() {
    }

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

    public static boolean isTransient(Throwable error) {
        if (error instanceof TransactionInProgressException || error instanceof TransactionConflictException
                || error instanceof ProvisionedThroughputExceededException
                || error instanceof RequestLimitExceededException) {
            return true;
        }
        if (error instanceof AwsServiceException serviceException) {
            return serviceException.isThrottlingException() || serviceException.statusCode() >= 500;
        }
        return error instanceof SdkClientException clientException && isTransientClientFailure(clientException);
    }

    private static boolean isTransientClientFailure(SdkClientException error) {
        if (error instanceof NonRetryableException) {
            return false;
        }
        if (error instanceof ApiCallTimeoutException || error instanceof ApiCallAttemptTimeoutException
                || error.retryable()) {
            return true;
        }
        Throwable cause = error.getCause();
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof IOException || cause instanceof UncheckedIOException
                    || cause instanceof TimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
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
        if (codes.stream().allMatch(code -> code == null || NO_FAILURE.equals(code))
                || codes.stream().anyMatch(code -> code != null && TRANSIENT_CANCELLATION_CODES.contains(code))) {
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
