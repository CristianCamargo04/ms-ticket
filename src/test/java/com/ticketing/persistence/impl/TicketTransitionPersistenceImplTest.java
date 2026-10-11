package com.ticketing.persistence.impl;

import static com.ticketing.persistence.PersistenceFixtures.EVENT_ID;
import static com.ticketing.persistence.PersistenceFixtures.NOW;
import static com.ticketing.persistence.PersistenceFixtures.ORDER_ID;
import static com.ticketing.persistence.PersistenceFixtures.TABLES;
import static com.ticketing.persistence.PersistenceFixtures.completed;
import static com.ticketing.persistence.PersistenceFixtures.failed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.domain.data.TicketTransitionData;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import com.ticketing.persistence.impl.mapper.TicketTransitionEntityMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

@ExtendWith(MockitoExtension.class)
class TicketTransitionPersistenceImplTest {
    private static final TicketTransitionData RESERVED = transition("t-1", TicketStatusEnum.AVAILABLE,
            TicketStatusEnum.RESERVED, TransitionReasonEnum.RESERVATION, 0);
    private static final TicketTransitionData RELEASED = transition("t-2", TicketStatusEnum.RESERVED,
            TicketStatusEnum.AVAILABLE, TransitionReasonEnum.HOLD_EXPIRED, 600);

    @Mock
    private DynamoDbAsyncClient dynamoDb;

    private final TicketTransitionEntityMapper mapper = new TicketTransitionEntityMapper();
    private TicketTransitionPersistenceImpl persistence;

    @BeforeEach
    void setUp() {
        persistence = new TicketTransitionPersistenceImpl(dynamoDb, TABLES, mapper);
    }

    @Test
    void shouldReadTheWholeHistoryOfATicketInOrder() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(completed(QueryResponse.builder().items(List.of(mapper.toItem(RESERVED)))
                        .lastEvaluatedKey(Map.of("ticketKey", AttributeValue.fromS(EVENT_ID + "#T-000001"))).build()))
                .thenReturn(completed(QueryResponse.builder().items(List.of(mapper.toItem(RELEASED))).build()));

        StepVerifier.create(persistence.findByTicket(EVENT_ID, "T-000001"))
                .expectNext(RESERVED, RELEASED)
                .verifyComplete();

        ArgumentCaptor<QueryRequest> requests = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb, times(2)).query(requests.capture());
        QueryRequest first = requests.getAllValues().getFirst();
        assertThat(first.tableName()).isEqualTo("t-ticket-transitions");
        assertThat(first.keyConditionExpression()).isEqualTo("ticketKey = :ticketKey");
        assertThat(first.expressionAttributeValues())
                .isEqualTo(Map.of(":ticketKey", AttributeValue.fromS(EVENT_ID + "#T-000001")));
        assertThat(first.scanIndexForward()).isTrue();
        assertThat(first.consistentRead()).isTrue();
    }

    @Test
    void shouldReturnAnEmptyHistory() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(completed(QueryResponse.builder().build()));

        StepVerifier.create(persistence.findByTicket(EVENT_ID, "T-000002")).verifyComplete();
    }

    @Test
    void shouldPropagateQueryFailures() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(failed(ResourceNotFoundException.builder().message("no table").build()));

        StepVerifier.create(persistence.findByTicket(EVENT_ID, "T-000002"))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    private static TicketTransitionData transition(String id, TicketStatusEnum from, TicketStatusEnum to,
                                                   TransitionReasonEnum reason, long secondsAfterNow) {
        return new TicketTransitionData(EVENT_ID, "T-000001", id, from, to, ORDER_ID, reason, "actor",
                NOW.plusSeconds(secondsAfterNow));
    }
}
