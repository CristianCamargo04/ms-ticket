package com.ticketing.persistence.impl.support;

import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * Lazily walks the pages of a DynamoDB query.
 *
 * <p>The next page is requested only after the previous one has been consumed and only while the subscriber still
 * wants items, so {@code items(...).take(n)} never reads more pages than needed. Every page call is deferred, which
 * makes the whole stream safe to re-subscribe (retries).
 */
public final class QueryPaginator {

    private QueryPaginator() {
    }

    /**
     * Items of every page of a query.
     *
     * @param dynamoDb the client
     * @param request  the first page request
     * @return the items in query order
     */
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
