package com.ticketing.persistence.impl;

import static com.ticketing.persistence.impl.support.DynamoAttributes.epochMillis;
import static com.ticketing.persistence.impl.support.DynamoAttributes.number;
import static com.ticketing.persistence.impl.support.DynamoAttributes.storedPrecision;
import static com.ticketing.persistence.impl.support.DynamoAttributes.string;
import static com.ticketing.persistence.impl.support.DynamoAttributes.stringList;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.data.TicketTransitionData;
import com.ticketing.domain.inventory.ConfirmInventoryCommand;
import com.ticketing.domain.inventory.FinalizeSaleCommand;
import com.ticketing.domain.inventory.InventoryCommand;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import com.ticketing.domain.inventory.IssueComplimentaryCommand;
import com.ticketing.domain.inventory.ReleaseInventoryCommand;
import com.ticketing.domain.inventory.ReserveInventoryCommand;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.persistence.InventoryTransactionPersistence;
import com.ticketing.persistence.mapper.TicketEntityMapper;
import com.ticketing.persistence.mapper.TicketTransitionEntityMapper;
import com.ticketing.persistence.impl.support.DeadlinePartitioner;
import com.ticketing.persistence.impl.support.DynamoDbTableNames;
import com.ticketing.persistence.impl.support.TransactionCancellationTranslator;
import com.ticketing.util.DeterministicIdUtil;
import com.ticketing.util.HashUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

@Repository
public class InventoryTransactionPersistenceImpl implements InventoryTransactionPersistence {
    static final int CLIENT_REQUEST_TOKEN_LENGTH = 36;

    private static final String STATUS_PLACEHOLDER = "#status";
    private static final Map<String, String> STATUS_NAME = Map.of(STATUS_PLACEHOLDER, "status");
    private static final String EVENT_ON_SALE_WITH_STOCK = "#status = :onSale AND availableCount >= :q";
    private static final String EVENT_EXISTS = "attribute_exists(eventId)";
    private static final String TICKET_IS_AVAILABLE = "#status = :from";
    private static final String TICKET_IS_HELD_BY_ORDER = "#status = :from AND orderId = :orderId";

    private final DynamoDbAsyncClient dynamoDb;
    private final DynamoDbTableNames tables;
    private final DeadlinePartitioner partitioner;
    private final TicketTransitionEntityMapper transitionMapper;
    private final Supplier<String> nonceSupplier;

    @Autowired
    public InventoryTransactionPersistenceImpl(DynamoDbAsyncClient dynamoDb, DynamoDbTableNames tables,
                                               DeadlinePartitioner partitioner,
                                               TicketTransitionEntityMapper transitionMapper) {
        this(dynamoDb, tables, partitioner, transitionMapper, () -> UUID.randomUUID().toString());
    }

    InventoryTransactionPersistenceImpl(DynamoDbAsyncClient dynamoDb, DynamoDbTableNames tables,
                                        DeadlinePartitioner partitioner,
                                        TicketTransitionEntityMapper transitionMapper,
                                        Supplier<String> nonceSupplier) {
        this.dynamoDb = dynamoDb;
        this.tables = tables;
        this.partitioner = partitioner;
        this.transitionMapper = transitionMapper;
        this.nonceSupplier = nonceSupplier;
    }

    @Override
    public Mono<InventoryWriteOutcome> reserve(ReserveInventoryCommand command) {
        OrderData order = command.order();
        AttributeValue holdExpiresAt = epochMillis(command.holdExpiresAt());
        return execute("reserve", command,
                new ItemChange("availableCount = availableCount - :q, reservedCount = reservedCount + :q", null,
                        EVENT_ON_SALE_WITH_STOCK, Map.of(":onSale", string(EventStatusEnum.ON_SALE.name()))),
                new ItemChange("#status = :to, statusKey = :statusKey, orderId = :orderId, "
                        + "holdExpiresAt = :holdExpiresAt", null, TICKET_IS_AVAILABLE,
                        Map.of(":holdExpiresAt", holdExpiresAt)),
                new ItemChange("ticketStatus = :ticketStatus, processingStatus = :processingStatus, "
                        + "ticketIds = :ticketIds, holdExpiresAt = :holdExpiresAt, "
                        + "deadlinePartition = :deadlinePartition, deadlineAt = :holdExpiresAt", null,
                        "processingStatus = :expectedProcessingStatus",
                        Map.of(":ticketStatus", string(TicketStatusEnum.RESERVED.name()),
                                ":processingStatus", string(OrderProcessingStatusEnum.ACTIVE.name()),
                                ":ticketIds", stringList(command.ticketIds()),
                                ":holdExpiresAt", holdExpiresAt,
                                ":deadlinePartition", string(partitioner.partitionOf(
                                        DeadlineTypeEnum.HOLD_EXPIRATION, order.orderId())),
                                ":expectedProcessingStatus", string(OrderProcessingStatusEnum.QUEUED.name()))),
                () -> order.withReservation(command.ticketIds(), storedPrecision(command.holdExpiresAt()),
                        storedPrecision(command.now())));
    }

    @Override
    public Mono<InventoryWriteOutcome> issueComplimentary(IssueComplimentaryCommand command) {
        OrderData order = command.order();
        return execute("issue-complimentary", command,
                new ItemChange("availableCount = availableCount - :q, complimentaryCount = complimentaryCount + :q",
                        null, EVENT_ON_SALE_WITH_STOCK, Map.of(":onSale", string(EventStatusEnum.ON_SALE.name()))),
                new ItemChange("#status = :to, statusKey = :statusKey, orderId = :orderId", null,
                        TICKET_IS_AVAILABLE, Map.of()),
                new ItemChange("ticketStatus = :ticketStatus, processingStatus = :processingStatus, "
                        + "ticketIds = :ticketIds", "deadlinePartition, deadlineAt",
                        "processingStatus = :expectedProcessingStatus",
                        Map.of(":ticketStatus", string(TicketStatusEnum.COMPLIMENTARY.name()),
                                ":processingStatus", string(OrderProcessingStatusEnum.COMPLETED.name()),
                                ":ticketIds", stringList(command.ticketIds()),
                                ":expectedProcessingStatus", string(OrderProcessingStatusEnum.QUEUED.name()))),
                () -> order.withComplimentaryIssued(command.ticketIds(), storedPrecision(command.now())));
    }

    @Override
    public Mono<InventoryWriteOutcome> confirm(ConfirmInventoryCommand command) {
        OrderData order = command.order();
        return execute("confirm", command,
                new ItemChange("reservedCount = reservedCount - :q, pendingCount = pendingCount + :q", null,
                        EVENT_EXISTS, Map.of()),
                new ItemChange("#status = :to, statusKey = :statusKey", null, TICKET_IS_HELD_BY_ORDER, Map.of()),
                new ItemChange("ticketStatus = :ticketStatus, deadlinePartition = :deadlinePartition, "
                        + "deadlineAt = :deadlineAt", null,
                        "ticketStatus = :expectedTicketStatus AND processingStatus = :expectedProcessingStatus "
                                + "AND holdExpiresAt > :now AND customerId = :customerId",
                        Map.of(":ticketStatus", string(TicketStatusEnum.PENDING_CONFIRMATION.name()),
                                ":deadlinePartition", string(partitioner.partitionOf(
                                        DeadlineTypeEnum.PAYMENT_CONFIRMATION, order.orderId())),
                                ":deadlineAt", epochMillis(command.confirmationDeadline()),
                                ":expectedTicketStatus", string(TicketStatusEnum.RESERVED.name()),
                                ":expectedProcessingStatus", string(OrderProcessingStatusEnum.ACTIVE.name()),
                                ":customerId", string(command.actor()))),
                () -> order.withPendingConfirmation(storedPrecision(command.confirmationDeadline()),
                        storedPrecision(command.now())));
    }

    @Override
    public Mono<InventoryWriteOutcome> finalizeSale(FinalizeSaleCommand command) {
        OrderData order = command.order();
        return execute("finalize-sale", command,
                new ItemChange("pendingCount = pendingCount - :q, soldCount = soldCount + :q", null, EVENT_EXISTS,
                        Map.of()),
                new ItemChange("#status = :to, statusKey = :statusKey", "holdExpiresAt", TICKET_IS_HELD_BY_ORDER,
                        Map.of()),
                new ItemChange("ticketStatus = :ticketStatus, processingStatus = :processingStatus, "
                        + "paymentReference = :paymentReference", "deadlinePartition, deadlineAt",
                        "ticketStatus = :expectedTicketStatus AND processingStatus = :expectedProcessingStatus",
                        Map.of(":ticketStatus", string(TicketStatusEnum.SOLD.name()),
                                ":processingStatus", string(OrderProcessingStatusEnum.COMPLETED.name()),
                                ":paymentReference", string(command.paymentReference()),
                                ":expectedTicketStatus", string(TicketStatusEnum.PENDING_CONFIRMATION.name()),
                                ":expectedProcessingStatus", string(OrderProcessingStatusEnum.ACTIVE.name()))),
                () -> order.withSale(command.paymentReference(), storedPrecision(command.now())));
    }

    @Override
    public Mono<InventoryWriteOutcome> release(ReleaseInventoryCommand command) {
        OrderData order = command.order();
        String heldCounter = command.expectedStatus() == TicketStatusEnum.RESERVED ? "reservedCount" : "pendingCount";
        String timeGuard = switch (command.reason()) {
            case HOLD_EXPIRED -> " AND holdExpiresAt <= :now";
            case CONFIRMATION_TIMEOUT -> " AND deadlineAt <= :now";
            default -> "";
        };
        return execute("release-" + command.reason().name(), command,
                new ItemChange("availableCount = availableCount + :q, "
                        + heldCounter + " = " + heldCounter + " - :q", null, EVENT_EXISTS, Map.of()),
                new ItemChange("#status = :to, statusKey = :statusKey", "orderId, holdExpiresAt",
                        TICKET_IS_HELD_BY_ORDER, Map.of()),
                new ItemChange("ticketStatus = :ticketStatus, processingStatus = :processingStatus, "
                        + "reason = :reason", "deadlinePartition, deadlineAt",
                        "ticketStatus = :expectedTicketStatus AND processingStatus = :expectedProcessingStatus"
                                + timeGuard,
                        Map.of(":ticketStatus", string(TicketStatusEnum.AVAILABLE.name()),
                                ":processingStatus", string(command.resultingStatus().name()),
                                ":reason", string(command.reason().name()),
                                ":expectedTicketStatus", string(command.expectedStatus().name()),
                                ":expectedProcessingStatus", string(OrderProcessingStatusEnum.ACTIVE.name()))),
                () -> order.withRelease(command.resultingStatus(), command.reason().name(),
                        storedPrecision(command.now())));
    }

    static String clientRequestToken(String operation, String orderId, List<String> ticketIds, Instant now,
                                     String nonce) {
        String sortedTicketIds = ticketIds.stream().sorted().collect(Collectors.joining(","));
        return HashUtil.sha256Hex(String.join("|", operation, orderId, sortedTicketIds,
                Long.toString(now.toEpochMilli()), nonce)).substring(0, CLIENT_REQUEST_TOKEN_LENGTH);
    }

    private Mono<InventoryWriteOutcome> execute(String operation, InventoryCommand command, ItemChange eventChange,
                                                ItemChange ticketChange, ItemChange orderChange,
                                                Supplier<OrderData> appliedOrder) {
        String description = operation + " of order " + command.order().orderId();
        return Mono.defer(() -> Mono.fromFuture(dynamoDb.transactWriteItems(
                        request(operation, command, eventChange, ticketChange, orderChange))))
                .<InventoryWriteOutcome>map(committed -> new InventoryWriteOutcome.Applied(appliedOrder.get()))
                .onErrorResume(error -> TransactionCancellationTranslator.translate(error, command.quantity(),
                        description));
    }

    private TransactWriteItemsRequest request(String operation, InventoryCommand command, ItemChange eventChange,
                                              ItemChange ticketChange, ItemChange orderChange) {
        OrderData order = command.order();
        String token = clientRequestToken(operation, order.orderId(), command.ticketIds(), command.now(),
                nonceSupplier.get());
        List<TransactWriteItem> items = new ArrayList<>(2 * command.quantity() + 2);
        items.add(update(tables.events(), Map.of("eventId", string(order.eventId())), eventChange,
                Map.of(":q", number(command.quantity())), command.now()));
        for (String ticketId : command.ticketIds()) {
            items.add(update(tables.tickets(),
                    Map.of("eventId", string(order.eventId()), "ticketId", string(ticketId)), ticketChange,
                    Map.of(":to", string(command.toStatus().name()),
                            ":from", string(command.fromStatus().name()),
                            ":statusKey", string(TicketEntityMapper.statusKey(command.toStatus(), ticketId)),
                            ":orderId", string(order.orderId())),
                    command.now()));
        }
        items.add(update(tables.orders(), Map.of("orderId", string(order.orderId())), orderChange, Map.of(),
                command.now()));
        for (String ticketId : command.ticketIds()) {
            items.add(auditRecord(command, token, ticketId));
        }
        return TransactWriteItemsRequest.builder()
                .clientRequestToken(token)
                .transactItems(items)
                .build();
    }

    private static TransactWriteItem update(String table, Map<String, AttributeValue> key, ItemChange change,
                                            Map<String, AttributeValue> implicitValues, Instant now) {
        String expression = "SET " + change.set() + ", updatedAt = :now"
                + (change.remove() == null ? "" : " REMOVE " + change.remove())
                + " ADD version :one";
        Map<String, AttributeValue> values = new HashMap<>(change.values());
        values.putAll(implicitValues);
        values.put(":now", epochMillis(now));
        values.put(":one", number(1));
        Update.Builder update = Update.builder()
                .tableName(table)
                .key(key)
                .updateExpression(expression)
                .conditionExpression(change.condition())
                .expressionAttributeValues(values);
        if (expression.contains(STATUS_PLACEHOLDER) || change.condition().contains(STATUS_PLACEHOLDER)) {
            update.expressionAttributeNames(STATUS_NAME);
        }
        return TransactWriteItem.builder().update(update.build()).build();
    }

    private TransactWriteItem auditRecord(InventoryCommand command, String token, String ticketId) {
        OrderData order = command.order();
        TicketTransitionData transition = new TicketTransitionData(order.eventId(), ticketId,
                DeterministicIdUtil.nameBasedId(token + "#" + ticketId), command.fromStatus(), command.toStatus(),
                order.orderId(), command.reason(), command.actor(), command.now());
        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tables.ticketTransitions())
                        .item(transitionMapper.toItem(transition))
                        .conditionExpression("attribute_not_exists(ticketKey)")
                        .build())
                .build();
    }

    private record ItemChange(String set, String remove, String condition, Map<String, AttributeValue> values) {
    }
}
