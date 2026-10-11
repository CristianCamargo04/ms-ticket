package com.ticketing.persistence.impl.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.test.StepVerifier;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactionConflictException;
import software.amazon.awssdk.services.dynamodb.model.TransactionInProgressException;

/**
 * Layout for two tickets: [0] event, [1..2] tickets, [3] order, [4..5] audit records.
 */
class TransactionCancellationTranslatorTest {

    private static final int TICKETS = 2;
    private static final String NONE = "None";
    private static final String FAILED = TransactionCancellationTranslator.CONDITIONAL_CHECK_FAILED;

    static Stream<Arguments> businessCancellations() {
        return Stream.of(
                Arguments.of(codes(FAILED, FAILED, NONE, FAILED, NONE, NONE),
                        InventoryWriteOutcome.OrderStateChanged.class),
                Arguments.of(codes(NONE, NONE, NONE, FAILED, NONE, NONE),
                        InventoryWriteOutcome.OrderStateChanged.class),
                Arguments.of(codes(FAILED, NONE, FAILED, NONE, NONE, NONE),
                        InventoryWriteOutcome.InsufficientInventory.class),
                Arguments.of(codes(NONE, NONE, FAILED, NONE, NONE, NONE),
                        InventoryWriteOutcome.StaleTicketSelection.class),
                Arguments.of(codes(NONE, FAILED, "TransactionConflict", NONE, NONE, NONE),
                        InventoryWriteOutcome.StaleTicketSelection.class));
    }

    @ParameterizedTest
    @MethodSource("businessCancellations")
    void shouldMapConditionFailuresByItemIndexWithPriority(List<String> codes,
                                                          Class<? extends InventoryWriteOutcome> expected) {
        StepVerifier.create(TransactionCancellationTranslator.translate(canceled(codes), TICKETS, "reserve"))
                .assertNext(outcome -> assertThat(outcome).isInstanceOf(expected))
                .verifyComplete();
    }

    @ParameterizedTest
    @MethodSource("transientCancellations")
    void shouldMapTransientCancellationsToARetryableConflict(List<String> codes) {
        TransactionCanceledException canceled = canceled(codes);

        StepVerifier.create(TransactionCancellationTranslator.translate(canceled, TICKETS, "reserve of order o-1"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(InventoryConflictException.class)
                            .hasCause(canceled)
                            .hasMessageContaining("reserve of order o-1");
                    assertThat(((InventoryConflictException) error).retryable()).isTrue();
                })
                .verify();
    }

    static Stream<List<String>> transientCancellations() {
        return Stream.of(
                codes(NONE, NONE, NONE, NONE, "TransactionConflict", NONE),
                codes("ThrottlingError", NONE, NONE, NONE, NONE, NONE),
                codes(NONE, "ProvisionedThroughputExceeded", NONE, NONE, NONE, NONE),
                codes(NONE, NONE, NONE, "RequestLimitExceeded", NONE, NONE),
                codes(NONE, NONE, NONE, "InternalServerError", NONE, NONE),
                codes(NONE, NONE, NONE, NONE, NONE, FAILED),
                List.of());
    }

    @Test
    void shouldPropagateNonTransientCancellations() {
        TransactionCanceledException canceled = canceled(codes(NONE, "ValidationError", NONE, NONE, NONE, NONE));

        StepVerifier.create(TransactionCancellationTranslator.translate(canceled, TICKETS, "reserve"))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(canceled))
                .verify();
    }

    @Test
    void shouldTreatACancellationWithoutReasonsAsARetryableConflict() {
        TransactionCanceledException canceled = TransactionCanceledException.builder().message("cancelled").build();

        StepVerifier.create(TransactionCancellationTranslator.translate(canceled, TICKETS, "x"))
                .expectError(InventoryConflictException.class)
                .verify();
    }

    @Test
    void shouldToleratePartialReasonLists() {
        StepVerifier.create(TransactionCancellationTranslator.translate(canceled(codes(FAILED)), TICKETS, "x"))
                .assertNext(outcome -> assertThat(outcome)
                        .isInstanceOf(InventoryWriteOutcome.InsufficientInventory.class))
                .verifyComplete();
    }

    @Test
    void shouldUnwrapCompletionExceptions() {
        TransactionCanceledException canceled = canceled(codes(NONE, NONE, NONE, FAILED, NONE, NONE));

        StepVerifier.create(TransactionCancellationTranslator.translate(new CompletionException(canceled), TICKETS,
                        "x"))
                .assertNext(outcome -> assertThat(outcome).isInstanceOf(InventoryWriteOutcome.OrderStateChanged.class))
                .verifyComplete();
        StepVerifier.create(TransactionCancellationTranslator.translate(new CompletionException(null), TICKETS,
                        "x"))
                .expectError(CompletionException.class)
                .verify();
    }

    @Test
    void shouldMapTransientFailuresOutsideCancellations() {
        SdkClientException timeout = ApiCallTimeoutException.create(1000);

        StepVerifier.create(TransactionCancellationTranslator.translate(timeout, TICKETS, "confirm"))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(InventoryConflictException.class)
                        .hasCause(timeout))
                .verify();
    }

    @Test
    void shouldPropagateOtherFailuresUnchanged() {
        IllegalStateException bug = new IllegalStateException("bug");

        StepVerifier.create(TransactionCancellationTranslator.translate(bug, TICKETS, "confirm"))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(bug))
                .verify();
    }

    @Test
    void shouldClassifyTransientFailures() {
        assertThat(TransactionCancellationTranslator.isTransient(
                TransactionInProgressException.builder().build())).isTrue();
        assertThat(TransactionCancellationTranslator.isTransient(
                TransactionConflictException.builder().build())).isTrue();
        assertThat(TransactionCancellationTranslator.isTransient(
                ProvisionedThroughputExceededException.builder().build())).isTrue();
        assertThat(TransactionCancellationTranslator.isTransient(
                RequestLimitExceededException.builder().build())).isTrue();
        assertThat(TransactionCancellationTranslator.isTransient(serviceError("ThrottlingException", 400))).isTrue();
        assertThat(TransactionCancellationTranslator.isTransient(serviceError("InternalFailure", 500))).isTrue();
        assertThat(TransactionCancellationTranslator.isTransient(serviceError("ServiceUnavailable", 503))).isTrue();
        assertThat(TransactionCancellationTranslator.isTransient(SdkClientException.create("connection reset")))
                .isTrue();
        assertThat(TransactionCancellationTranslator.isTransient(serviceError("ValidationException", 400)))
                .isFalse();
        assertThat(TransactionCancellationTranslator.isTransient(new IllegalArgumentException())).isFalse();
    }

    private static AwsServiceException serviceError(String code, int status) {
        return DynamoDbException.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
                .build();
    }

    private static TransactionCanceledException canceled(List<String> codes) {
        return TransactionCanceledException.builder()
                .message("Transaction cancelled")
                .cancellationReasons(codes.stream().map(code -> CancellationReason.builder().code(code).build())
                        .toList())
                .build();
    }

    private static List<String> codes(String... codes) {
        return Arrays.asList(codes);
    }
}
