package com.ticketing.persistence.impl;

import static com.ticketing.persistence.PersistenceFixtures.CUSTOMER_ID;
import static com.ticketing.persistence.PersistenceFixtures.EVENT_ID;
import static com.ticketing.persistence.PersistenceFixtures.NOW;
import static com.ticketing.persistence.PersistenceFixtures.ORDER_ID;
import static com.ticketing.persistence.PersistenceFixtures.TABLES;
import static com.ticketing.persistence.PersistenceFixtures.completed;
import static com.ticketing.persistence.PersistenceFixtures.failed;
import static com.ticketing.persistence.PersistenceFixtures.pendingOrder;
import static com.ticketing.persistence.PersistenceFixtures.queuedOrder;
import static com.ticketing.persistence.PersistenceFixtures.reservedOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.inventory.ConfirmInventoryCommand;
import com.ticketing.domain.inventory.FinalizeSaleCommand;
import com.ticketing.domain.inventory.InventoryCommand;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import com.ticketing.domain.inventory.IssueComplimentaryCommand;
import com.ticketing.domain.inventory.ReleaseInventoryCommand;
import com.ticketing.domain.inventory.ReserveInventoryCommand;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import com.ticketing.persistence.impl.mapper.OrderEntityMapper;
import com.ticketing.persistence.impl.mapper.TicketTransitionEntityMapper;
import com.ticketing.persistence.impl.support.DeadlinePartitioner;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

@ExtendWith(MockitoExtension.class)
class InventoryTransactionPersistenceImplTest {

    private static final List<String> TICKETS = List.of("T-000007", "T-000003");
    private static final Instant HOLD_EXPIRES_AT = NOW.plus(Duration.ofMinutes(10));
    private static final AttributeValue NOW_MILLIS = millis(NOW);
    private static final AttributeValue ONE = AttributeValue.fromN("1");
    private static final Map<String, String> STATUS_NAME = Map.of("#status", "status");
    /** What {@code Clock.systemUTC()} returns: finer than the millisecond precision of the stored numbers. */
    private static final Instant PRECISE_NOW = Instant.parse("2026-10-10T12:00:00.123456789Z");
    private static final Instant STORED_NOW = Instant.parse("2026-10-10T12:00:00.123Z");

    @Mock
    private DynamoDbAsyncClient dynamoDb;

    private final DeadlinePartitioner partitioner = new DeadlinePartitioner(TABLES);
    private InventoryTransactionPersistenceImpl persistence;

    @BeforeEach
    void setUp() {
        persistence = new InventoryTransactionPersistenceImpl(dynamoDb, TABLES, partitioner,
                new TicketTransitionEntityMapper());
    }

    @Test
    void shouldReserveTicketsInOneConditionalTransaction() {
        committed();
        OrderData order = queuedOrder(OrderTypeEnum.STANDARD, 2);
        ReserveInventoryCommand command = new ReserveInventoryCommand(order, TICKETS, HOLD_EXPIRES_AT, NOW, "worker");

        StepVerifier.create(persistence.reserve(command))
                .expectNext(new InventoryWriteOutcome.Applied(order.withReservation(TICKETS, HOLD_EXPIRES_AT, NOW)))
                .verifyComplete();

        TransactWriteItemsRequest request = capturedRequest();
        assertThat(request.clientRequestToken()).isEqualTo(InventoryTransactionPersistenceImpl
                .clientRequestToken("reserve", ORDER_ID, TICKETS, NOW)).hasSize(36);
        assertThat(request.transactItems()).hasSize(2 * TICKETS.size() + 2);
        assertEventUpdate(request, "SET availableCount = availableCount - :q, reservedCount = reservedCount + :q, "
                        + "updatedAt = :now ADD version :one", "#status = :onSale AND availableCount >= :q",
                Map.of(":q", AttributeValue.fromN("2"), ":onSale", AttributeValue.fromS("ON_SALE"),
                        ":now", NOW_MILLIS, ":one", ONE), STATUS_NAME);
        assertTicketUpdates(request, "SET #status = :to, statusKey = :statusKey, orderId = :orderId, "
                        + "holdExpiresAt = :holdExpiresAt, updatedAt = :now ADD version :one", "#status = :from",
                TicketStatusEnum.AVAILABLE, TicketStatusEnum.RESERVED, Map.of(":holdExpiresAt", millis(HOLD_EXPIRES_AT)));
        Update orderUpdate = orderUpdate(request);
        assertThat(orderUpdate.updateExpression()).isEqualTo("SET ticketStatus = :ticketStatus, "
                + "processingStatus = :processingStatus, ticketIds = :ticketIds, holdExpiresAt = :holdExpiresAt, "
                + "deadlinePartition = :deadlinePartition, deadlineAt = :holdExpiresAt, updatedAt = :now "
                + "ADD version :one");
        assertThat(orderUpdate.conditionExpression()).isEqualTo("processingStatus = :expectedProcessingStatus");
        assertThat(orderUpdate.hasExpressionAttributeNames()).isFalse();
        assertThat(orderUpdate.expressionAttributeValues()).isEqualTo(Map.of(
                ":ticketStatus", AttributeValue.fromS("RESERVED"),
                ":processingStatus", AttributeValue.fromS("ACTIVE"),
                ":ticketIds", AttributeValue.fromL(List.of(AttributeValue.fromS("T-000007"),
                        AttributeValue.fromS("T-000003"))),
                ":holdExpiresAt", millis(HOLD_EXPIRES_AT),
                ":deadlinePartition", AttributeValue.fromS(partitioner.partitionOf(DeadlineTypeEnum.HOLD_EXPIRATION,
                        ORDER_ID)),
                ":expectedProcessingStatus", AttributeValue.fromS("QUEUED"),
                ":now", NOW_MILLIS,
                ":one", ONE));
        assertAuditRecords(request, TicketStatusEnum.AVAILABLE, TicketStatusEnum.RESERVED,
                TransitionReasonEnum.RESERVATION, "worker");
    }

    @Test
    void shouldIssueComplimentaryTickets() {
        committed();
        OrderData order = queuedOrder(OrderTypeEnum.COMPLIMENTARY, 2);

        StepVerifier.create(persistence.issueComplimentary(new IssueComplimentaryCommand(order, TICKETS, NOW, "admin")))
                .expectNext(new InventoryWriteOutcome.Applied(order.withComplimentaryIssued(TICKETS, NOW)))
                .verifyComplete();

        TransactWriteItemsRequest request = capturedRequest();
        assertEventUpdate(request, "SET availableCount = availableCount - :q, "
                        + "complimentaryCount = complimentaryCount + :q, updatedAt = :now ADD version :one",
                "#status = :onSale AND availableCount >= :q",
                Map.of(":q", AttributeValue.fromN("2"), ":onSale", AttributeValue.fromS("ON_SALE"),
                        ":now", NOW_MILLIS, ":one", ONE), STATUS_NAME);
        assertTicketUpdates(request, "SET #status = :to, statusKey = :statusKey, orderId = :orderId, "
                        + "updatedAt = :now ADD version :one", "#status = :from",
                TicketStatusEnum.AVAILABLE, TicketStatusEnum.COMPLIMENTARY, Map.of());
        Update orderUpdate = orderUpdate(request);
        assertThat(orderUpdate.updateExpression()).isEqualTo("SET ticketStatus = :ticketStatus, "
                + "processingStatus = :processingStatus, ticketIds = :ticketIds, updatedAt = :now "
                + "REMOVE deadlinePartition, deadlineAt ADD version :one");
        assertThat(orderUpdate.conditionExpression()).isEqualTo("processingStatus = :expectedProcessingStatus");
        assertThat(orderUpdate.expressionAttributeValues()).containsEntry(":ticketStatus",
                AttributeValue.fromS("COMPLIMENTARY")).containsEntry(":processingStatus",
                AttributeValue.fromS("COMPLETED"));
        assertAuditRecords(request, TicketStatusEnum.AVAILABLE, TicketStatusEnum.COMPLIMENTARY,
                TransitionReasonEnum.COMPLIMENTARY_ISSUED, "admin");
    }

    @Test
    void shouldConfirmOnlyAnUnexpiredHoldOfTheCustomer() {
        committed();
        OrderData order = reservedOrder(TICKETS);
        Instant deadline = NOW.plus(Duration.ofMinutes(5));

        StepVerifier.create(persistence.confirm(new ConfirmInventoryCommand(order, deadline, NOW, CUSTOMER_ID)))
                .expectNext(new InventoryWriteOutcome.Applied(order.withPendingConfirmation(deadline, NOW)))
                .verifyComplete();

        TransactWriteItemsRequest request = capturedRequest();
        assertEventUpdate(request, "SET reservedCount = reservedCount - :q, pendingCount = pendingCount + :q, "
                        + "updatedAt = :now ADD version :one", "attribute_exists(eventId)",
                Map.of(":q", AttributeValue.fromN("2"), ":now", NOW_MILLIS, ":one", ONE), null);
        assertTicketUpdates(request, "SET #status = :to, statusKey = :statusKey, updatedAt = :now ADD version :one",
                "#status = :from AND orderId = :orderId", TicketStatusEnum.RESERVED,
                TicketStatusEnum.PENDING_CONFIRMATION, Map.of());
        Update orderUpdate = orderUpdate(request);
        assertThat(orderUpdate.updateExpression()).isEqualTo("SET ticketStatus = :ticketStatus, "
                + "deadlinePartition = :deadlinePartition, deadlineAt = :deadlineAt, updatedAt = :now "
                + "ADD version :one");
        assertThat(orderUpdate.conditionExpression()).isEqualTo("ticketStatus = :expectedTicketStatus "
                + "AND processingStatus = :expectedProcessingStatus AND holdExpiresAt > :now "
                + "AND customerId = :customerId");
        assertThat(orderUpdate.expressionAttributeValues()).isEqualTo(Map.of(
                ":ticketStatus", AttributeValue.fromS("PENDING_CONFIRMATION"),
                ":deadlinePartition", AttributeValue.fromS(partitioner.partitionOf(
                        DeadlineTypeEnum.PAYMENT_CONFIRMATION, ORDER_ID)),
                ":deadlineAt", millis(deadline),
                ":expectedTicketStatus", AttributeValue.fromS("RESERVED"),
                ":expectedProcessingStatus", AttributeValue.fromS("ACTIVE"),
                ":customerId", AttributeValue.fromS(CUSTOMER_ID),
                ":now", NOW_MILLIS,
                ":one", ONE));
        assertAuditRecords(request, TicketStatusEnum.RESERVED, TicketStatusEnum.PENDING_CONFIRMATION,
                TransitionReasonEnum.CUSTOMER_CONFIRMATION, CUSTOMER_ID);
    }

    @Test
    void shouldFinalizeASale() {
        committed();
        OrderData order = pendingOrder(TICKETS);

        StepVerifier.create(persistence.finalizeSale(new FinalizeSaleCommand(order, "pay-9", NOW, "payment-worker")))
                .expectNext(new InventoryWriteOutcome.Applied(order.withSale("pay-9", NOW)))
                .verifyComplete();

        TransactWriteItemsRequest request = capturedRequest();
        assertEventUpdate(request, "SET pendingCount = pendingCount - :q, soldCount = soldCount + :q, "
                        + "updatedAt = :now ADD version :one", "attribute_exists(eventId)",
                Map.of(":q", AttributeValue.fromN("2"), ":now", NOW_MILLIS, ":one", ONE), null);
        assertTicketUpdates(request, "SET #status = :to, statusKey = :statusKey, updatedAt = :now "
                        + "REMOVE holdExpiresAt ADD version :one", "#status = :from AND orderId = :orderId",
                TicketStatusEnum.PENDING_CONFIRMATION, TicketStatusEnum.SOLD, Map.of());
        Update orderUpdate = orderUpdate(request);
        assertThat(orderUpdate.updateExpression()).isEqualTo("SET ticketStatus = :ticketStatus, "
                + "processingStatus = :processingStatus, paymentReference = :paymentReference, updatedAt = :now "
                + "REMOVE deadlinePartition, deadlineAt ADD version :one");
        assertThat(orderUpdate.conditionExpression())
                .isEqualTo("ticketStatus = :expectedTicketStatus AND processingStatus = :expectedProcessingStatus");
        assertThat(orderUpdate.expressionAttributeValues()).containsEntry(":paymentReference",
                AttributeValue.fromS("pay-9")).containsEntry(":expectedTicketStatus",
                AttributeValue.fromS("PENDING_CONFIRMATION"));
        assertAuditRecords(request, TicketStatusEnum.PENDING_CONFIRMATION, TicketStatusEnum.SOLD,
                TransitionReasonEnum.PAYMENT_APPROVED, "payment-worker");
    }

    static Stream<Arguments> releases() {
        return Stream.of(
                Arguments.of(TicketStatusEnum.RESERVED, OrderProcessingStatusEnum.EXPIRED,
                        TransitionReasonEnum.HOLD_EXPIRED, "reservedCount", " AND holdExpiresAt <= :now"),
                Arguments.of(TicketStatusEnum.RESERVED, OrderProcessingStatusEnum.CANCELLED,
                        TransitionReasonEnum.CUSTOMER_CANCELLATION, "reservedCount", ""),
                Arguments.of(TicketStatusEnum.PENDING_CONFIRMATION, OrderProcessingStatusEnum.EXPIRED,
                        TransitionReasonEnum.CONFIRMATION_TIMEOUT, "pendingCount", " AND deadlineAt <= :now"),
                Arguments.of(TicketStatusEnum.PENDING_CONFIRMATION, OrderProcessingStatusEnum.PAYMENT_DECLINED,
                        TransitionReasonEnum.PAYMENT_DECLINED, "pendingCount", ""));
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("releases")
    void shouldReleaseHeldTicketsBackToTheInventory(TicketStatusEnum expected, OrderProcessingStatusEnum resulting,
                                                   TransitionReasonEnum reason, String heldCounter,
                                                   String timeGuard) {
        committed();
        OrderData order = expected == TicketStatusEnum.RESERVED ? reservedOrder(TICKETS) : pendingOrder(TICKETS);

        StepVerifier.create(persistence.release(new ReleaseInventoryCommand(order, expected, resulting, reason, NOW,
                        "sweeper")))
                .expectNext(new InventoryWriteOutcome.Applied(order.withRelease(resulting, reason.name(), NOW)))
                .verifyComplete();

        TransactWriteItemsRequest request = capturedRequest();
        assertThat(request.clientRequestToken()).isEqualTo(InventoryTransactionPersistenceImpl
                .clientRequestToken("release-" + reason.name(), ORDER_ID, TICKETS, NOW));
        assertEventUpdate(request, "SET availableCount = availableCount + :q, " + heldCounter + " = "
                        + heldCounter + " - :q, updatedAt = :now ADD version :one", "attribute_exists(eventId)",
                Map.of(":q", AttributeValue.fromN("2"), ":now", NOW_MILLIS, ":one", ONE), null);
        assertTicketUpdates(request, "SET #status = :to, statusKey = :statusKey, updatedAt = :now "
                        + "REMOVE orderId, holdExpiresAt ADD version :one", "#status = :from AND orderId = :orderId",
                expected, TicketStatusEnum.AVAILABLE, Map.of());
        Update orderUpdate = orderUpdate(request);
        assertThat(orderUpdate.updateExpression()).isEqualTo("SET ticketStatus = :ticketStatus, "
                + "processingStatus = :processingStatus, reason = :reason, updatedAt = :now "
                + "REMOVE deadlinePartition, deadlineAt ADD version :one");
        assertThat(orderUpdate.conditionExpression()).isEqualTo("ticketStatus = :expectedTicketStatus "
                + "AND processingStatus = :expectedProcessingStatus" + timeGuard);
        assertThat(orderUpdate.expressionAttributeValues()).isEqualTo(Map.of(
                ":ticketStatus", AttributeValue.fromS("AVAILABLE"),
                ":processingStatus", AttributeValue.fromS(resulting.name()),
                ":reason", AttributeValue.fromS(reason.name()),
                ":expectedTicketStatus", AttributeValue.fromS(expected.name()),
                ":expectedProcessingStatus", AttributeValue.fromS("ACTIVE"),
                ":now", NOW_MILLIS,
                ":one", ONE));
        assertAuditRecords(request, expected, TicketStatusEnum.AVAILABLE, reason, "sweeper");
    }

    static Stream<Arguments> subMillisecondCommands() {
        return Stream.of(
                Arguments.of(new ReserveInventoryCommand(queuedOrder(OrderTypeEnum.STANDARD, 2), TICKETS,
                        PRECISE_NOW.plus(Duration.ofMinutes(10)), PRECISE_NOW, "worker")),
                Arguments.of(new IssueComplimentaryCommand(queuedOrder(OrderTypeEnum.COMPLIMENTARY, 2), TICKETS,
                        PRECISE_NOW, "admin")),
                Arguments.of(new ConfirmInventoryCommand(reservedOrder(TICKETS),
                        PRECISE_NOW.plus(Duration.ofMinutes(5)), PRECISE_NOW, CUSTOMER_ID)),
                Arguments.of(new FinalizeSaleCommand(pendingOrder(TICKETS), "pay-9", PRECISE_NOW,
                        "payment-worker")),
                Arguments.of(new ReleaseInventoryCommand(reservedOrder(TICKETS), TicketStatusEnum.RESERVED,
                        OrderProcessingStatusEnum.EXPIRED, TransitionReasonEnum.HOLD_EXPIRED, PRECISE_NOW,
                        "sweeper")));
    }

    /**
     * The applied order is what a later read returns: every instant the transaction writes is stored as epoch
     * milliseconds, so the in-memory result must not keep the sub-millisecond part of the command instants.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("subMillisecondCommands")
    void shouldReturnTheAppliedOrderWithTheStoragePrecision(InventoryCommand command) {
        committed();
        OrderEntityMapper orderMapper = new OrderEntityMapper(partitioner);

        StepVerifier.create(execute(command))
                .assertNext(outcome -> {
                    OrderData applied = ((InventoryWriteOutcome.Applied) outcome).order();
                    assertThat(applied).isEqualTo(orderMapper.fromItem(orderMapper.toItem(applied)));
                    assertThat(applied.updatedAt()).isEqualTo(STORED_NOW);
                    assertThat(applied.version()).isEqualTo(command.order().version() + 1);
                    if (applied.holdExpiresAt() != null) {
                        assertThat(applied.holdExpiresAt().getNano() % 1_000_000).isZero();
                    }
                    if (applied.deadlineAt() != null) {
                        assertThat(applied.deadlineAt().getNano() % 1_000_000).isZero();
                    }
                })
                .verifyComplete();

        TransactWriteItemsRequest request = capturedRequest();
        assertThat(orderUpdate(request).expressionAttributeValues()).containsEntry(":now", millis(STORED_NOW));
    }

    @Test
    void shouldApplyTheTruncatedHoldAndDeadlineInMemory() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(completed(TransactWriteItemsResponse.builder().build()));
        Instant preciseHold = PRECISE_NOW.plus(Duration.ofMinutes(10));
        Instant preciseDeadline = PRECISE_NOW.plus(Duration.ofMinutes(5));
        OrderData queued = queuedOrder(OrderTypeEnum.STANDARD, 2);
        OrderData reserved = reservedOrder(TICKETS);

        StepVerifier.create(persistence.reserve(new ReserveInventoryCommand(queued, TICKETS, preciseHold,
                        PRECISE_NOW, "worker")))
                .expectNext(new InventoryWriteOutcome.Applied(queued.withReservation(TICKETS,
                        Instant.ofEpochMilli(preciseHold.toEpochMilli()), STORED_NOW)))
                .verifyComplete();
        StepVerifier.create(persistence.confirm(new ConfirmInventoryCommand(reserved, preciseDeadline, PRECISE_NOW,
                        CUSTOMER_ID)))
                .expectNext(new InventoryWriteOutcome.Applied(reserved.withPendingConfirmation(
                        Instant.ofEpochMilli(preciseDeadline.toEpochMilli()), STORED_NOW)))
                .verifyComplete();
    }

    private Mono<InventoryWriteOutcome> execute(InventoryCommand command) {
        return switch (command) {
            case ReserveInventoryCommand reserve -> persistence.reserve(reserve);
            case IssueComplimentaryCommand complimentary -> persistence.issueComplimentary(complimentary);
            case ConfirmInventoryCommand confirm -> persistence.confirm(confirm);
            case FinalizeSaleCommand sale -> persistence.finalizeSale(sale);
            case ReleaseInventoryCommand release -> persistence.release(release);
        };
    }

    @Test
    void shouldKeepTheClientRequestTokenStableForOneCommand() {
        String token = InventoryTransactionPersistenceImpl.clientRequestToken("reserve", ORDER_ID, TICKETS, NOW);

        assertThat(InventoryTransactionPersistenceImpl.clientRequestToken("reserve", ORDER_ID,
                List.of("T-000003", "T-000007"), NOW)).as("independent of the ticket order").isEqualTo(token);
        assertThat(token).hasSize(36).matches("[0-9a-f]+");
        assertThat(InventoryTransactionPersistenceImpl.clientRequestToken("confirm", ORDER_ID, TICKETS, NOW))
                .isNotEqualTo(token);
        assertThat(InventoryTransactionPersistenceImpl.clientRequestToken("reserve", "other", TICKETS, NOW))
                .isNotEqualTo(token);
        assertThat(InventoryTransactionPersistenceImpl.clientRequestToken("reserve", ORDER_ID, List.of("T-000007"),
                NOW)).isNotEqualTo(token);
        assertThat(InventoryTransactionPersistenceImpl.clientRequestToken("reserve", ORDER_ID, TICKETS,
                NOW.plusMillis(1))).as("a new attempt is a new command").isNotEqualTo(token);
    }

    @Test
    void shouldSendTheSameTokenAndAuditIdsWhenTheSameCommandIsRetried() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failed(TransactionCanceledException.builder().message("conflict")
                        .cancellationReasons(reasons("None", "None", "None", "None", "TransactionConflict", "None"))
                        .build()))
                .thenReturn(completed(TransactWriteItemsResponse.builder().build()));
        OrderData order = queuedOrder(OrderTypeEnum.STANDARD, 2);
        Mono<InventoryWriteOutcome> reserve = persistence.reserve(
                new ReserveInventoryCommand(order, TICKETS, HOLD_EXPIRES_AT, NOW, "worker"));
        verifyNoInteractions(dynamoDb);

        StepVerifier.create(reserve.retry(1))
                .expectNextMatches(InventoryWriteOutcome.Applied.class::isInstance)
                .verifyComplete();

        ArgumentCaptor<TransactWriteItemsRequest> requests = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb, times(2)).transactWriteItems(requests.capture());
        assertThat(requests.getAllValues().get(1)).isEqualTo(requests.getAllValues().getFirst());
    }

    static Stream<Arguments> cancellations() {
        return Stream.of(
                Arguments.of(reasons("ConditionalCheckFailed", "None", "None", "None", "None", "None"),
                        new InventoryWriteOutcome.InsufficientInventory()),
                Arguments.of(reasons("None", "None", "ConditionalCheckFailed", "None", "None", "None"),
                        new InventoryWriteOutcome.StaleTicketSelection()),
                Arguments.of(reasons("ConditionalCheckFailed", "None", "None", "ConditionalCheckFailed", "None",
                        "None"), new InventoryWriteOutcome.OrderStateChanged()));
    }

    @ParameterizedTest
    @MethodSource("cancellations")
    void shouldTranslateConditionFailures(List<CancellationReason> reasons, InventoryWriteOutcome expected) {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failed(TransactionCanceledException.builder().message("cancelled")
                        .cancellationReasons(reasons).build()));

        StepVerifier.create(persistence.reserve(new ReserveInventoryCommand(queuedOrder(OrderTypeEnum.STANDARD, 2),
                        TICKETS, HOLD_EXPIRES_AT, NOW, "worker")))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldSignalARetryableConflictOnTransactionConflicts() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failed(TransactionCanceledException.builder().message("conflict")
                        .cancellationReasons(reasons("TransactionConflict", "None", "None", "None", "None", "None"))
                        .build()));

        StepVerifier.create(persistence.confirm(new ConfirmInventoryCommand(reservedOrder(TICKETS),
                        NOW.plusSeconds(300), NOW, CUSTOMER_ID)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(InventoryConflictException.class)
                        .hasMessageContaining("confirm of order " + ORDER_ID))
                .verify();
    }

    private void committed() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(completed(TransactWriteItemsResponse.builder().build()));
    }

    private TransactWriteItemsRequest capturedRequest() {
        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb).transactWriteItems(request.capture());
        return request.getValue();
    }

    private static void assertEventUpdate(TransactWriteItemsRequest request, String expression, String condition,
                                          Map<String, AttributeValue> values, Map<String, String> names) {
        Update event = request.transactItems().getFirst().update();
        assertThat(event.tableName()).isEqualTo("t-events");
        assertThat(event.key()).isEqualTo(Map.of("eventId", AttributeValue.fromS(EVENT_ID)));
        assertThat(event.updateExpression()).isEqualTo(expression);
        assertThat(event.conditionExpression()).isEqualTo(condition);
        assertThat(event.expressionAttributeValues()).isEqualTo(values);
        if (names == null) {
            assertThat(event.hasExpressionAttributeNames()).isFalse();
        } else {
            assertThat(event.expressionAttributeNames()).isEqualTo(names);
        }
    }

    private static void assertTicketUpdates(TransactWriteItemsRequest request, String expression, String condition,
                                            TicketStatusEnum from, TicketStatusEnum to,
                                            Map<String, AttributeValue> extraValues) {
        for (int index = 0; index < TICKETS.size(); index++) {
            String ticketId = TICKETS.get(index);
            Update ticket = request.transactItems().get(1 + index).update();
            assertThat(ticket.tableName()).isEqualTo("t-tickets");
            assertThat(ticket.key()).isEqualTo(Map.of("eventId", AttributeValue.fromS(EVENT_ID),
                    "ticketId", AttributeValue.fromS(ticketId)));
            assertThat(ticket.updateExpression()).isEqualTo(expression);
            assertThat(ticket.conditionExpression()).isEqualTo(condition);
            assertThat(ticket.expressionAttributeNames()).isEqualTo(STATUS_NAME);
            assertThat(ticket.expressionAttributeValues())
                    .containsAllEntriesOf(extraValues)
                    .containsEntry(":from", AttributeValue.fromS(from.name()))
                    .containsEntry(":to", AttributeValue.fromS(to.name()))
                    .containsEntry(":statusKey", AttributeValue.fromS(to.name() + "#" + ticketId))
                    .containsEntry(":orderId", AttributeValue.fromS(ORDER_ID))
                    .containsEntry(":now", NOW_MILLIS)
                    .containsEntry(":one", ONE)
                    .hasSize(6 + extraValues.size());
        }
    }

    private static Update orderUpdate(TransactWriteItemsRequest request) {
        Update order = request.transactItems().get(TICKETS.size() + 1).update();
        assertThat(order.tableName()).isEqualTo("t-orders");
        assertThat(order.key()).isEqualTo(Map.of("orderId", AttributeValue.fromS(ORDER_ID)));
        return order;
    }

    private static void assertAuditRecords(TransactWriteItemsRequest request, TicketStatusEnum from,
                                           TicketStatusEnum to, TransitionReasonEnum reason, String actor) {
        List<TransactWriteItem> audit = request.transactItems().subList(TICKETS.size() + 2,
                request.transactItems().size());
        assertThat(audit).hasSize(TICKETS.size());
        for (int index = 0; index < TICKETS.size(); index++) {
            Put put = audit.get(index).put();
            assertThat(put.tableName()).isEqualTo("t-ticket-transitions");
            assertThat(put.conditionExpression()).isEqualTo("attribute_not_exists(ticketKey)");
            Map<String, AttributeValue> item = put.item();
            assertThat(item.get("ticketKey").s()).isEqualTo(EVENT_ID + "#" + TICKETS.get(index));
            assertThat(item.get("transitionKey").s()).startsWith(NOW.toEpochMilli() + "#");
            assertThat(item.get("ticketId").s()).isEqualTo(TICKETS.get(index));
            assertThat(item.get("orderId").s()).isEqualTo(ORDER_ID);
            assertThat(item.get("fromStatus").s()).isEqualTo(from.name());
            assertThat(item.get("toStatus").s()).isEqualTo(to.name());
            assertThat(item.get("reason").s()).isEqualTo(reason.name());
            assertThat(item.get("actor").s()).isEqualTo(actor);
            assertThat(item.get("occurredAt")).isEqualTo(NOW_MILLIS);
        }
        assertThat(audit).extracting(item -> item.put().item().get("transitionKey").s()).doesNotHaveDuplicates();
    }

    private static List<CancellationReason> reasons(String... codes) {
        return Stream.of(codes).map(code -> CancellationReason.builder().code(code).build()).toList();
    }

    private static AttributeValue millis(Instant instant) {
        return AttributeValue.fromN(Long.toString(instant.toEpochMilli()));
    }
}
