package com.ticketing.persistence.impl.support;

import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

public final class QueryPaginator {
    private QueryPaginator() {
    }

    public static Flux<Map<String, AttributeValue>> items(DynamoDbAsyncClient dynamoDb, QueryRequest request) {
        return Mono.fromFuture(() -> dynamoDb.query(request))
                .flatMapMany(response -> hasNextPage(response)
                        ? Flux.fromIterable(response.items()).concatWith(Flux.defer(() -> items(dynamoDb,
                                request.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build())))
                        : Flux.fromIterable(response.items()));
    }

    private static boolean hasNextPage(QueryResponse response) {
        return response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty();
    }
}
