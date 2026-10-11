package com.ticketing.persistence.impl;

import static com.ticketing.persistence.PersistenceFixtures.EVENT_ID;
import static com.ticketing.persistence.PersistenceFixtures.NOW;
import static com.ticketing.persistence.PersistenceFixtures.TABLES;
import static com.ticketing.persistence.PersistenceFixtures.completed;
import static com.ticketing.persistence.PersistenceFixtures.event;
import static com.ticketing.persistence.PersistenceFixtures.failed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.persistence.mapper.EventEntityMapper;
import com.ticketing.persistence.impl.support.CursorCodec;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

@ExtendWith(MockitoExtension.class)
class EventPersistenceImplTest {
    private static final Map<String, AttributeValue> LAST_KEY = Map.of(
            "eventId", AttributeValue.fromS(EVENT_ID),
            "status", AttributeValue.fromS("ON_SALE"),
            "eventDate", AttributeValue.fromN("1798750800000"));

    @Mock
    private DynamoDbAsyncClient dynamoDb;

    private final EventEntityMapper mapper = EventEntityMapper.INSTANCE;
    private EventPersistenceImpl persistence;

    @BeforeEach
    void setUp() {
        persistence = new EventPersistenceImpl(dynamoDb,
                DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(dynamoDb).build(), TABLES, mapper);
    }

    @Test
    void shouldReturnTheCreatedEventWithTheStoragePrecision() {
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(completed(PutItemResponse.builder().build()));
        Instant precise = Instant.parse("2026-10-10T12:00:00.123456789Z");
        Instant stored = Instant.parse("2026-10-10T12:00:00.123Z");
        EventData event = EventData.newPublishing(EVENT_ID, "Concert", "Arena", precise.plusSeconds(86_400), 10,
                new BigDecimal("49.90"), "USD", precise);

        StepVerifier.create(persistence.create(event))
                .assertNext(created -> {
                    assertThat(created).isEqualTo(mapper.fromItem(mapper.toItem(event)));
                    assertThat(created.createdAt()).isEqualTo(stored);
                    assertThat(created.updatedAt()).isEqualTo(stored);
                    assertThat(created.eventDate()).isEqualTo(stored.plusSeconds(86_400));
                })
                .verifyComplete();
    }

    @Test
    void shouldCreateAnEventOnlyIfAbsent() {
        when(dynamoDb.putItem(any(PutItemRequest.class))).thenReturn(completed(PutItemResponse.builder().build()));
        EventData event = event();

        StepVerifier.create(persistence.create(event)).expectNext(event).verifyComplete();

        ArgumentCaptor<PutItemRequest> request = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(request.capture());
        assertThat(request.getValue().tableName()).isEqualTo("t-events");
        assertThat(request.getValue().conditionExpression()).isEqualTo("attribute_not_exists(eventId)");
        assertThat(request.getValue().item()).isEqualTo(mapper.toItem(event));
    }

    @Test
    void shouldPropagateACreationFailure() {
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(failed(ConditionalCheckFailedException.builder().message("exists").build()));

        StepVerifier.create(persistence.create(event())).expectError(ConditionalCheckFailedException.class).verify();
    }

    @Test
    void shouldReadAnEventWithAStronglyConsistentRead() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(completed(GetItemResponse.builder().item(mapper.toItem(event())).build()));

        StepVerifier.create(persistence.findById(EVENT_ID)).expectNext(event()).verifyComplete();

        ArgumentCaptor<GetItemRequest> request = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamoDb).getItem(request.capture());
        assertThat(request.getValue().tableName()).isEqualTo("t-events");
        assertThat(request.getValue().key()).isEqualTo(Map.of("eventId", AttributeValue.fromS(EVENT_ID)));
        assertThat(request.getValue().consistentRead()).isTrue();
    }

    @Test
    void shouldReturnEmptyWhenTheEventDoesNotExist() {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(completed(GetItemResponse.builder().build()));

        StepVerifier.create(persistence.findById(EVENT_ID)).verifyComplete();
    }

    @Test
    void shouldQueryTheFirstPageOfAStatus() {
        when(dynamoDb.query(any(QueryRequest.class))).thenReturn(completed(QueryResponse.builder()
                .items(List.of(mapper.toItem(event())))
                .lastEvaluatedKey(LAST_KEY)
                .build()));

        StepVerifier.create(persistence.findByStatus(EventStatusEnum.ON_SALE, 20, null))
                .assertNext(page -> {
                    assertThat(page.items()).containsExactly(event());
                    assertThat(page.hasMore()).isTrue();
                    assertThat(CursorCodec.decode(page.nextCursor(), Map.of(
                            "eventId", ScalarAttributeType.S,
                            "status", ScalarAttributeType.S,
                            "eventDate", ScalarAttributeType.N)))
                            .isEqualTo(LAST_KEY);
                })
                .verifyComplete();

        ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb).query(request.capture());
        assertThat(request.getValue().tableName()).isEqualTo("t-events");
        assertThat(request.getValue().indexName()).isEqualTo("status-eventDate-index");
        assertThat(request.getValue().keyConditionExpression()).isEqualTo("#status = :status");
        assertThat(request.getValue().expressionAttributeNames()).isEqualTo(Map.of("#status", "status"));
        assertThat(request.getValue().expressionAttributeValues())
                .isEqualTo(Map.of(":status", AttributeValue.fromS("ON_SALE")));
        assertThat(request.getValue().limit()).isEqualTo(20);
        assertThat(request.getValue().hasExclusiveStartKey()).isFalse();
    }

    @Test
    void shouldContinueFromTheCursorAndEndWithoutOne() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(completed(QueryResponse.builder().items(List.of()).build()));

        StepVerifier.create(persistence.findByStatus(EventStatusEnum.ON_SALE, 5, CursorCodec.encode(LAST_KEY)))
                .assertNext(page -> {
                    assertThat(page.items()).isEmpty();
                    assertThat(page.nextCursor()).isNull();
                })
                .verifyComplete();

        ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb).query(request.capture());
        assertThat(request.getValue().exclusiveStartKey()).isEqualTo(LAST_KEY);
    }

    @Test
    void shouldRejectMalformedCursorsWithoutCallingDynamoDb() {
        StepVerifier.create(persistence.findByStatus(EventStatusEnum.ON_SALE, 5, "%%%"))
                .expectError(DomainValidationException.class)
                .verify();
        StepVerifier.create(persistence.findByStatus(EventStatusEnum.CLOSED, 5, CursorCodec.encode(LAST_KEY)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(DomainValidationException.class)
                        .hasMessageContaining("CLOSED"))
                .verify();
        StepVerifier.create(persistence.findByStatus(EventStatusEnum.ON_SALE, 0, null))
                .expectError(IllegalArgumentException.class)
                .verify();

        verifyNoInteractions(dynamoDb);
    }

    @Test
    void shouldUpdateTheStatusConditionally() {
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(completed(UpdateItemResponse.builder().build()));

        StepVerifier.create(persistence.updateStatus(EVENT_ID, EventStatusEnum.PUBLISHING, EventStatusEnum.ON_SALE,
                NOW)).expectNext(true).verifyComplete();

        ArgumentCaptor<UpdateItemRequest> request = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDb).updateItem(request.capture());
        assertThat(request.getValue().tableName()).isEqualTo("t-events");
        assertThat(request.getValue().key()).isEqualTo(Map.of("eventId", AttributeValue.fromS(EVENT_ID)));
        assertThat(request.getValue().updateExpression())
                .isEqualTo("SET #status = :target, updatedAt = :now ADD version :one");
        assertThat(request.getValue().conditionExpression()).isEqualTo("#status = :expected");
        assertThat(request.getValue().expressionAttributeNames()).isEqualTo(Map.of("#status", "status"));
        assertThat(request.getValue().expressionAttributeValues()).isEqualTo(Map.of(
                ":target", AttributeValue.fromS("ON_SALE"),
                ":expected", AttributeValue.fromS("PUBLISHING"),
                ":now", AttributeValue.fromN(Long.toString(NOW.toEpochMilli())),
                ":one", AttributeValue.fromN("1")));
    }

    @Test
    void shouldReportAFailedStatusCondition() {
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(failed(ConditionalCheckFailedException.builder().message("no").build()));

        StepVerifier.create(persistence.updateStatus(EVENT_ID, EventStatusEnum.PUBLISHING, EventStatusEnum.ON_SALE,
                NOW)).expectNext(false).verifyComplete();
    }

    @Test
    void shouldPropagateOtherStatusUpdateFailures() {
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(failed(InternalServerErrorException.builder().message("boom").build()));

        StepVerifier.create(persistence.updateStatus(EVENT_ID, EventStatusEnum.PUBLISHING, EventStatusEnum.ON_SALE,
                NOW)).expectError(InternalServerErrorException.class).verify();
    }
}
