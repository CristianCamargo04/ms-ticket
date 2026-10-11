package com.ticketing.persistence.impl;

import static com.ticketing.persistence.PersistenceFixtures.EVENT_ID;
import static com.ticketing.persistence.PersistenceFixtures.TABLES;
import static com.ticketing.persistence.PersistenceFixtures.availableTicket;
import static com.ticketing.persistence.PersistenceFixtures.completed;
import static com.ticketing.persistence.PersistenceFixtures.failed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.domain.data.TicketData;
import com.ticketing.domain.event.EventPolicy;
import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.persistence.mapper.TicketEntityMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

@ExtendWith(MockitoExtension.class)
class TicketPersistenceImplTest {
    private static final String TABLE = "t-tickets";

    @Mock
    private DynamoDbAsyncClient dynamoDb;

    private final TicketEntityMapper mapper = TicketEntityMapper.INSTANCE;
    private TicketPersistenceImpl persistence;

    @BeforeEach
    void setUp() {
        persistence = new TicketPersistenceImpl(dynamoDb, TABLES, mapper,
                new EventPolicy(20_000, Duration.ofSeconds(1), 4));
    }

    @Test
    void shouldWriteTicketsInBatchesOf25() {
        when(dynamoDb.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(completed(BatchWriteItemResponse.builder().build()));

        StepVerifier.create(persistence.saveAll(tickets(60))).expectNext(60L).verifyComplete();

        ArgumentCaptor<BatchWriteItemRequest> requests = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(dynamoDb, times(3)).batchWriteItem(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(request ->
                assertThat(request.requestItems()).containsOnlyKeys(TABLE));
        assertThat(requests.getAllValues()).extracting(request -> request.requestItems().get(TABLE).size())
                .containsExactlyInAnyOrder(25, 25, 10);
        assertThat(requests.getAllValues()).flatExtracting(request -> request.requestItems().get(TABLE))
                .contains(put(availableTicket("T-000001")), put(availableTicket("T-000060")));
    }

    @Test
    void shouldWriteNothingForAnEmptyStream() {
        StepVerifier.create(persistence.saveAll(Flux.empty())).expectNext(0L).verifyComplete();

        verifyNoInteractions(dynamoDb);
    }

    @Test
    void shouldResubmitUnprocessedItemsAfterABackoff() {
        WriteRequest unprocessed = put(availableTicket("T-000003"));
        when(dynamoDb.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(completed(BatchWriteItemResponse.builder()
                        .unprocessedItems(Map.of(TABLE, List.of(unprocessed))).build()))
                .thenReturn(completed(BatchWriteItemResponse.builder().unprocessedItems(Map.of()).build()));

        StepVerifier.withVirtualTime(() -> persistence.saveAll(tickets(3)))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(49))
                .thenAwait(Duration.ofMillis(1))
                .expectNext(3L)
                .verifyComplete();

        ArgumentCaptor<BatchWriteItemRequest> requests = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(dynamoDb, times(2)).batchWriteItem(requests.capture());
        assertThat(requests.getAllValues().get(1).requestItems()).isEqualTo(Map.of(TABLE, List.of(unprocessed)));
    }

    @Test
    void shouldFailWhenItemsRemainUnprocessedAfterEveryAttempt() {
        when(dynamoDb.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(completed(BatchWriteItemResponse.builder()
                        .unprocessedItems(Map.of(TABLE, List.of(put(availableTicket("T-000001"))))).build()));

        StepVerifier.withVirtualTime(() -> persistence.saveAll(tickets(1)))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(10))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(InventoryConflictException.class)
                        .hasMessageContaining("8 attempts"))
                .verify();

        verify(dynamoDb, times(8)).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void shouldPropagateBatchWriteFailures() {
        when(dynamoDb.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(failed(InternalServerErrorException.builder().message("boom").build()));

        StepVerifier.create(persistence.saveAll(tickets(2))).expectError(InternalServerErrorException.class).verify();
    }

    @Test
    void shouldRangeScanTheAvailabilityIndex() {
        when(dynamoDb.query(any(QueryRequest.class))).thenReturn(completed(QueryResponse.builder()
                .items(List.of(indexItem("T-000010"), indexItem("T-000011")))
                .build()));

        StepVerifier.create(persistence.findAvailable(EVENT_ID, "T-000010", 9))
                .expectNext(availableTicket("T-000010"), availableTicket("T-000011"))
                .verifyComplete();

        ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb).query(request.capture());
        assertThat(request.getValue().tableName()).isEqualTo(TABLE);
        assertThat(request.getValue().indexName()).isEqualTo("eventId-statusKey-index");
        assertThat(request.getValue().keyConditionExpression())
                .isEqualTo("eventId = :eventId AND statusKey BETWEEN :from AND :to");
        assertThat(request.getValue().expressionAttributeValues()).isEqualTo(Map.of(
                ":eventId", AttributeValue.fromS(EVENT_ID),
                ":from", AttributeValue.fromS("AVAILABLE#T-000010"),
                ":to", AttributeValue.fromS("AVAILABLE$")));
        assertThat(request.getValue().limit()).isEqualTo(9);
        assertThat(request.getValue().consistentRead()).isNull();
    }

    @Test
    void shouldScanFromTheFirstTicketWithoutAStartTicket() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(completed(QueryResponse.builder().items(List.of()).build()));

        StepVerifier.create(persistence.findAvailable(EVENT_ID, null, 3)).verifyComplete();

        ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb).query(request.capture());
        assertThat(request.getValue().expressionAttributeValues().get(":from"))
                .isEqualTo(AttributeValue.fromS("AVAILABLE#"));
    }

    @Test
    void shouldStopReadingPagesOnceTheLimitIsReached() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(completed(QueryResponse.builder().items(List.of(indexItem("T-000001"), indexItem("T-000002")))
                        .lastEvaluatedKey(Map.of("ticketId", AttributeValue.fromS("T-000002"))).build()))
                .thenReturn(completed(QueryResponse.builder().items(List.of(indexItem("T-000003"), indexItem("T-000004")))
                        .lastEvaluatedKey(Map.of("ticketId", AttributeValue.fromS("T-000004"))).build()));

        StepVerifier.create(persistence.findAvailable(EVENT_ID, "", 3))
                .expectNext(availableTicket("T-000001"), availableTicket("T-000002"), availableTicket("T-000003"))
                .verifyComplete();

        verify(dynamoDb, times(2)).query(any(QueryRequest.class));
    }

    @Test
    void shouldReturnNoTicketForANonPositiveLimit() {
        StepVerifier.create(persistence.findAvailable(EVENT_ID, "", 0)).verifyComplete();

        verifyNoInteractions(dynamoDb);
    }

    @Test
    void shouldReadTicketsByIdInRequestOrder() {
        when(dynamoDb.batchGetItem(any(BatchGetItemRequest.class))).thenReturn(completed(BatchGetItemResponse.builder()
                .responses(Map.of(TABLE, List.of(mapper.toItem(availableTicket("T-000002")),
                        mapper.toItem(availableTicket("T-000001")))))
                .build()));

        StepVerifier.create(persistence.findByIds(EVENT_ID, List.of("T-000001", "T-000003", "T-000002", "T-000001")))
                .expectNext(availableTicket("T-000001"), availableTicket("T-000002"))
                .verifyComplete();

        ArgumentCaptor<BatchGetItemRequest> request = ArgumentCaptor.forClass(BatchGetItemRequest.class);
        verify(dynamoDb).batchGetItem(request.capture());
        KeysAndAttributes keys = request.getValue().requestItems().get(TABLE);
        assertThat(keys.consistentRead()).isTrue();
        assertThat(keys.keys()).containsExactly(key("T-000001"), key("T-000003"), key("T-000002"));
    }

    @Test
    void shouldReadIdsInChunksOf100() {
        when(dynamoDb.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(completed(BatchGetItemResponse.builder().responses(Map.of()).build()));
        List<String> ids = IntStream.rangeClosed(1, 150).mapToObj(TicketData::ticketIdFor).toList();

        StepVerifier.create(persistence.findByIds(EVENT_ID, ids)).verifyComplete();

        ArgumentCaptor<BatchGetItemRequest> requests = ArgumentCaptor.forClass(BatchGetItemRequest.class);
        verify(dynamoDb, times(2)).batchGetItem(requests.capture());
        assertThat(requests.getAllValues()).extracting(request -> request.requestItems().get(TABLE).keys().size())
                .containsExactly(100, 50);
    }

    @Test
    void shouldResubmitUnprocessedKeys() {
        KeysAndAttributes unprocessed = KeysAndAttributes.builder().keys(List.of(key("T-000002"))).consistentRead(true).build();
        when(dynamoDb.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(completed(BatchGetItemResponse.builder()
                        .responses(Map.of(TABLE, List.of(mapper.toItem(availableTicket("T-000001")))))
                        .unprocessedKeys(Map.of(TABLE, unprocessed))
                        .build()))
                .thenReturn(completed(BatchGetItemResponse.builder()
                        .responses(Map.of(TABLE, List.of(mapper.toItem(availableTicket("T-000002")))))
                        .unprocessedKeys(Map.of())
                        .build()));

        StepVerifier.withVirtualTime(() -> persistence.findByIds(EVENT_ID, List.of("T-000001", "T-000002")))
                .expectSubscription()
                .thenAwait(Duration.ofMillis(50))
                .expectNext(availableTicket("T-000001"), availableTicket("T-000002"))
                .verifyComplete();

        ArgumentCaptor<BatchGetItemRequest> requests = ArgumentCaptor.forClass(BatchGetItemRequest.class);
        verify(dynamoDb, times(2)).batchGetItem(requests.capture());
        assertThat(requests.getAllValues().get(1).requestItems()).isEqualTo(Map.of(TABLE, unprocessed));
    }

    @Test
    void shouldFailWhenKeysRemainUnprocessedAfterEveryAttempt() {
        when(dynamoDb.batchGetItem(any(BatchGetItemRequest.class))).thenReturn(completed(BatchGetItemResponse.builder()
                .unprocessedKeys(Map.of(TABLE, KeysAndAttributes.builder().keys(List.of(key("T-000001"))).build()))
                .build()));

        StepVerifier.withVirtualTime(() -> persistence.findByIds(EVENT_ID, List.of("T-000001")))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(10))
                .expectError(InventoryConflictException.class)
                .verify();

        verify(dynamoDb, times(8)).batchGetItem(any(BatchGetItemRequest.class));
    }

    @Test
    void shouldNotCallDynamoDbForNoIds() {
        StepVerifier.create(persistence.findByIds(EVENT_ID, List.of())).verifyComplete();

        verifyNoInteractions(dynamoDb);
    }

    private static Flux<TicketData> tickets(int count) {
        return Flux.range(1, count).map(sequence -> availableTicket(TicketData.ticketIdFor(sequence)));
    }

    private WriteRequest put(TicketData ticket) {
        return WriteRequest.builder().putRequest(PutRequest.builder().item(mapper.toItem(ticket)).build()).build();
    }

    private Map<String, AttributeValue> indexItem(String ticketId) {
        return mapper.toItem(availableTicket(ticketId));
    }

    private static Map<String, AttributeValue> key(String ticketId) {
        return Map.of("eventId", AttributeValue.fromS(EVENT_ID), "ticketId", AttributeValue.fromS(ticketId));
    }
}
