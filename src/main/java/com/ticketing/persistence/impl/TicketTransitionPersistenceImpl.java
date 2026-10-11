package com.ticketing.persistence.impl;

import static com.ticketing.persistence.impl.support.DynamoAttributes.string;

import com.ticketing.domain.data.TicketTransitionData;
import com.ticketing.persistence.TicketTransitionPersistence;
import com.ticketing.persistence.mapper.TicketTransitionEntityMapper;
import com.ticketing.persistence.impl.support.DynamoDbTableNames;
import com.ticketing.persistence.impl.support.QueryPaginator;
import java.util.Map;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

@Repository
public class TicketTransitionPersistenceImpl implements TicketTransitionPersistence {
    private final DynamoDbAsyncClient dynamoDb;
    private final String tableName;
    private final TicketTransitionEntityMapper mapper;

    public TicketTransitionPersistenceImpl(DynamoDbAsyncClient dynamoDb, DynamoDbTableNames tableNames,
                                           TicketTransitionEntityMapper mapper) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableNames.ticketTransitions();
        this.mapper = mapper;
    }

    @Override
    public Flux<TicketTransitionData> findByTicket(String eventId, String ticketId) {
        return Flux.defer(() -> QueryPaginator.items(dynamoDb, QueryRequest.builder()
                        .tableName(tableName)
                        .keyConditionExpression("ticketKey = :ticketKey")
                        .expressionAttributeValues(Map.of(":ticketKey",
                                string(TicketTransitionEntityMapper.ticketKey(eventId, ticketId))))
                        .scanIndexForward(true)
                        .consistentRead(true)
                        .build()))
                .map(mapper::fromItem);
    }
}
