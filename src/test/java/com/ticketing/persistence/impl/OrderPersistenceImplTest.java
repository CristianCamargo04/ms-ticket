package com.ticketing.persistence.impl;

import static com.ticketing.persistence.PersistenceFixtures.NOW;
import static com.ticketing.persistence.PersistenceFixtures.ORDER_ID;
import static com.ticketing.persistence.PersistenceFixtures.SHARDS;
import static com.ticketing.persistence.PersistenceFixtures.TABLES;
import static com.ticketing.persistence.PersistenceFixtures.completed;
import static com.ticketing.persistence.PersistenceFixtures.failed;
import static com.ticketing.persistence.PersistenceFixtures.queuedOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.domain.data.OrderData;
import com.ticketing.enums.DeadlineTypeEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.persistence.impl.mapper.OrderEntityMapper;
import com.ticketing.persistence.impl.support.DeadlinePartitioner;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

@ExtendWith(MockitoExtension.class)
class OrderPersistenceImplTest {

    private static final String TABLE = "t-orders";
    private static final AttributeValue NOW_MILLIS = AttributeValue.fromN(Long.toString(NOW.toEpochMilli()));

    @Mock
    private DynamoDbAsyncClient dynamoDb;

    private final DeadlinePartitioner partitioner = new DeadlinePartitioner(TABLES);
    private final OrderEntityMapper mapper = new OrderEntityMapper(partitioner);
    private OrderPersistenceImpl persistence;

    @BeforeEach
    void setUp() {
        persistence = new OrderPersistenceImpl(dynamoDb,
                DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(dynamoDb).build(), TABLES, mapper, partitioner);
    }

    @Test
    void shouldInsertAnOrderOnlyIfAbsent() {
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(completed(PutItemResponse.builder().build()));
        OrderData order = queuedOrder(OrderTypeEnum.STANDARD, 2);

        StepVerifier.create(persistence.insertIfAbsent(order)).expectNext(true).verifyComplete();

        ArgumentCaptor<PutItemRequest> request = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(request.capture());
        assertThat(request.getValue().tableName()).isEqualTo(TABLE);
        assertThat(request.getValue().conditionExpression()).isEqualTo("attribute_not_exists(orderId)");
        assertThat(request.getValue().item()).isEqualTo(mapper.toItem(order));
        assertThat(request.getValue().item()).containsKeys("deadlinePartition", "deadlineAt");
    }

    @Test
    void shouldReportAnExistingOrder() {
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(failed(ConditionalCheckFailedException.builder().message("exists").build()));

        StepVerifier.create(persistence.insertIfAbsent(queuedOrder(OrderTypeEnum.STANDARD, 1)))
                .expectNext(false).verifyComplete();
    }

    @Test
    void shouldPropagateOtherInsertFailures() {
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(failed(InternalServerErrorException.builder().message("boom").build()));

        StepVerifier.create(persistence.insertIfAbsent(queuedOrder(OrderTypeEnum.STANDARD, 1)))
                .expectError(InternalServerErrorException.class).verify();
    }

    @Test
    void shouldReadAnOrderWithAStronglyConsistentRead() {
        OrderData order = queuedOrder(OrderTypeEnum.COMPLIMENTARY, 3);
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(completed(GetItemResponse.builder().item(mapper.toItem(order)).build()));

        StepVerifier.create(persistence.findById(ORDER_ID)).expectNext(order).verifyComplete();

        ArgumentCaptor<GetItemRequest> request = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamoDb).getItem(request.capture());
        assertThat(request.getValue().tableName()).isEqualTo(TABLE);
        assertThat(request.getValue().key()).isEqualTo(Map.of("orderId", AttributeValue.fromS(ORDER_ID)));
        assertThat(request.getValue().consistentRead()).isTrue();
    }

    @Test
    void shouldReturnEmptyWhenTheOrderDoesNotExist() {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(completed(GetItemResponse.builder().build()));

        StepVerifier.create(persistence.findById(ORDER_ID)).verifyComplete();
    }

    @Test
    void shouldQueryEveryShardAndMergeByDeadline() {
        List<OrderData> due = IntStream.range(0, 3)
                .mapToObj(index -> order("order-" + index, NOW.minusSeconds(10 - index)))
                .toList();
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(completed(QueryResponse.builder().items(List.of(mapper.toItem(due.get(2)))).build()))
                .thenReturn(completed(QueryResponse.builder().items(List.of(mapper.toItem(due.get(0)),
                        mapper.toItem(due.get(1)))).build()))
                .thenReturn(completed(QueryResponse.builder().items(List.of()).build()));

        StepVerifier.create(persistence.findDueOrders(DeadlineTypeEnum.DISPATCH, NOW, 50))
                .expectNextSequence(due)
                .verifyComplete();

        ArgumentCaptor<QueryRequest> requests = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb, times(SHARDS)).query(requests.capture());
        assertThat(requests.getAllValues()).extracting(request -> request.expressionAttributeValues().get(":partition"))
                .containsExactlyElementsOf(partitioner.partitionsOf(DeadlineTypeEnum.DISPATCH).stream()
                        .map(AttributeValue::fromS).toList());
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.tableName()).isEqualTo(TABLE);
            assertThat(request.indexName()).isEqualTo("deadline-index");
            assertThat(request.keyConditionExpression())
                    .isEqualTo("deadlinePartition = :partition AND deadlineAt <= :now");
            assertThat(request.expressionAttributeValues().get(":now")).isEqualTo(NOW_MILLIS);
            assertThat(request.limit()).isEqualTo(50);
        });
    }

    @Test
    void shouldReturnAtMostLimitDueOrders() {
        when(dynamoDb.query(any(QueryRequest.class))).thenReturn(completed(QueryResponse.builder()
                .items(List.of(mapper.toItem(order("a", NOW.minusSeconds(3))), mapper.toItem(order("b", NOW.minusSeconds(2)))))
                .build()));

        StepVerifier.create(persistence.findDueOrders(DeadlineTypeEnum.DISPATCH, NOW, 2))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void shouldReturnNoDueOrderForANonPositiveLimit() {
        StepVerifier.create(persistence.findDueOrders(DeadlineTypeEnum.DISPATCH, NOW, 0)).verifyComplete();

        verifyNoInteractions(dynamoDb);
    }

    @Test
    void shouldPropagateAShardQueryFailure() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(failed(InternalServerErrorException.builder().message("boom").build()));

        StepVerifier.create(persistence.findDueOrders(DeadlineTypeEnum.HOLD_EXPIRATION, NOW, 10))
                .expectError(InternalServerErrorException.class)
                .verify();
    }

    @Test
    void shouldCloseAnOrderAndRemoveItsDeadline() {
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(completed(UpdateItemResponse.builder().build()));

        StepVerifier.create(persistence.transitionProcessingStatus(ORDER_ID, OrderProcessingStatusEnum.QUEUED,
                OrderProcessingStatusEnum.REJECTED, "SOLD_OUT", NOW)).expectNext(true).verifyComplete();

        UpdateItemRequest request = capturedUpdate();
        assertThat(request.tableName()).isEqualTo(TABLE);
        assertThat(request.key()).isEqualTo(Map.of("orderId", AttributeValue.fromS(ORDER_ID)));
        assertThat(request.updateExpression()).isEqualTo("SET processingStatus = :target, updatedAt = :now, "
                + "reason = :reason REMOVE deadlinePartition, deadlineAt ADD version :one");
        assertThat(request.conditionExpression()).isEqualTo("processingStatus = :expected");
        assertThat(request.expressionAttributeValues()).isEqualTo(Map.of(
                ":target", AttributeValue.fromS("REJECTED"),
                ":expected", AttributeValue.fromS("QUEUED"),
                ":reason", AttributeValue.fromS("SOLD_OUT"),
                ":now", NOW_MILLIS,
                ":one", AttributeValue.fromN("1")));
    }

    @Test
    void shouldClearTheReasonWhenNoneIsGiven() {
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(completed(UpdateItemResponse.builder().build()));

        StepVerifier.create(persistence.transitionProcessingStatus(ORDER_ID, OrderProcessingStatusEnum.QUEUED,
                OrderProcessingStatusEnum.CANCELLED, null, NOW)).expectNext(true).verifyComplete();

        UpdateItemRequest request = capturedUpdate();
        assertThat(request.updateExpression()).isEqualTo("SET processingStatus = :target, updatedAt = :now "
                + "REMOVE reason, deadlinePartition, deadlineAt ADD version :one");
        assertThat(request.expressionAttributeValues()).doesNotContainKey(":reason");
    }

    @Test
    void shouldKeepTheDeadlineForANonTerminalTarget() {
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(completed(UpdateItemResponse.builder().build()));

        persistence.transitionProcessingStatus(ORDER_ID, OrderProcessingStatusEnum.QUEUED,
                OrderProcessingStatusEnum.ACTIVE, "DISPATCHED", NOW).block();

        assertThat(capturedUpdate().updateExpression())
                .isEqualTo("SET processingStatus = :target, updatedAt = :now, reason = :reason ADD version :one");
    }

    @Test
    void shouldReportAFailedTransitionCondition() {
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(failed(ConditionalCheckFailedException.builder().message("no").build()));

        StepVerifier.create(persistence.transitionProcessingStatus(ORDER_ID, OrderProcessingStatusEnum.QUEUED,
                OrderProcessingStatusEnum.REJECTED, "SOLD_OUT", NOW)).expectNext(false).verifyComplete();
    }

    @Test
    void shouldPropagateOtherTransitionFailures() {
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(failed(InternalServerErrorException.builder().message("boom").build()));

        StepVerifier.create(persistence.transitionProcessingStatus(ORDER_ID, OrderProcessingStatusEnum.QUEUED,
                OrderProcessingStatusEnum.REJECTED, "SOLD_OUT", NOW))
                .expectError(InternalServerErrorException.class).verify();
    }

    @Test
    void shouldRescheduleTheDeadlineConditionally() {
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(completed(UpdateItemResponse.builder().build()));
        Instant deadline = NOW.plusSeconds(30);

        StepVerifier.create(persistence.rescheduleDeadline(ORDER_ID, OrderProcessingStatusEnum.QUEUED, deadline, NOW))
                .expectNext(true).verifyComplete();

        UpdateItemRequest request = capturedUpdate();
        assertThat(request.updateExpression())
                .isEqualTo("SET deadlineAt = :deadlineAt, updatedAt = :now ADD version :one");
        assertThat(request.conditionExpression()).isEqualTo("processingStatus = :expected");
        assertThat(request.expressionAttributeValues()).isEqualTo(Map.of(
                ":deadlineAt", AttributeValue.fromN(Long.toString(deadline.toEpochMilli())),
                ":expected", AttributeValue.fromS("QUEUED"),
                ":now", NOW_MILLIS,
                ":one", AttributeValue.fromN("1")));
    }

    @Test
    void shouldReportAFailedRescheduleCondition() {
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(failed(ConditionalCheckFailedException.builder().message("no").build()));

        StepVerifier.create(persistence.rescheduleDeadline(ORDER_ID, OrderProcessingStatusEnum.QUEUED,
                NOW.plusSeconds(30), NOW)).expectNext(false).verifyComplete();
    }

    private UpdateItemRequest capturedUpdate() {
        ArgumentCaptor<UpdateItemRequest> request = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDb).updateItem(request.capture());
        return request.getValue();
    }

    private static OrderData order(String orderId, Instant deadline) {
        return OrderData.newQueued(orderId, "event-1", "customer-1", OrderTypeEnum.STANDARD, 1, "hash", deadline,
                NOW.minusSeconds(60));
    }
}
