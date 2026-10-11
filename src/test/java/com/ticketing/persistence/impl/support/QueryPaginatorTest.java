package com.ticketing.persistence.impl.support;

import static com.ticketing.persistence.PersistenceFixtures.completed;
import static com.ticketing.persistence.PersistenceFixtures.failed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

@ExtendWith(MockitoExtension.class)
class QueryPaginatorTest {

    private static final QueryRequest REQUEST = QueryRequest.builder().tableName("t").limit(2).build();
    private static final Map<String, AttributeValue> LAST_KEY = Map.of("id", AttributeValue.fromS("2"));

    @Mock
    private DynamoDbAsyncClient dynamoDb;

    @Test
    void shouldReadASinglePage() {
        when(dynamoDb.query(any(QueryRequest.class))).thenReturn(completed(page(List.of(item("1")), Map.of())));

        StepVerifier.create(QueryPaginator.items(dynamoDb, REQUEST)).expectNext(item("1")).verifyComplete();
    }

    @Test
    void shouldFollowTheLastEvaluatedKey() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(completed(page(List.of(item("1"), item("2")), LAST_KEY)))
                .thenReturn(completed(page(List.of(item("3")), null)));

        StepVerifier.create(QueryPaginator.items(dynamoDb, REQUEST))
                .expectNext(item("1"), item("2"), item("3"))
                .verifyComplete();

        ArgumentCaptor<QueryRequest> requests = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb, times(2)).query(requests.capture());
        assertThat(requests.getAllValues().getFirst().hasExclusiveStartKey()).isFalse();
        assertThat(requests.getAllValues().get(1).exclusiveStartKey()).isEqualTo(LAST_KEY);
        assertThat(requests.getAllValues().get(1).tableName()).isEqualTo("t");
    }

    @Test
    void shouldNotReadMorePagesThanConsumed() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(completed(page(List.of(item("1"), item("2")), LAST_KEY)));

        StepVerifier.create(QueryPaginator.items(dynamoDb, REQUEST).take(2))
                .expectNext(item("1"), item("2"))
                .verifyComplete();

        verify(dynamoDb, times(1)).query(any(QueryRequest.class));
    }

    @Test
    void shouldBeLazyAndRetryable() {
        Flux<Map<String, AttributeValue>> items = QueryPaginator.items(dynamoDb, REQUEST);
        verifyNoInteractions(dynamoDb);
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenReturn(failed(ResourceNotFoundException.builder().message("missing").build()))
                .thenReturn(completed(page(List.of(item("1")), null)));

        StepVerifier.create(items.retry(1)).expectNext(item("1")).verifyComplete();
        verify(dynamoDb, times(2)).query(any(QueryRequest.class));
    }

    private static QueryResponse page(List<Map<String, AttributeValue>> items, Map<String, AttributeValue> lastKey) {
        QueryResponse.Builder response = QueryResponse.builder().items(items);
        if (lastKey != null) {
            response.lastEvaluatedKey(lastKey);
        }
        return response.build();
    }

    private static Map<String, AttributeValue> item(String id) {
        return Map.of("id", AttributeValue.fromS(id));
    }
}
