package com.ticketing.persistence.entity;

import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
@Getter
@Setter
public class TicketTransitionEntity {
    @Getter(onMethod_ = @DynamoDbPartitionKey)
    private String ticketKey;
    @Getter(onMethod_ = @DynamoDbSortKey)
    private String transitionKey;
    private String eventId;
    private String ticketId;
    private String orderId;
    private String fromStatus;
    private String toStatus;
    private String reason;
    private String actor;
    private Long occurredAt;
}
