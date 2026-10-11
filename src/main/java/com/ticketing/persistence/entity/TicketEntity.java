package com.ticketing.persistence.entity;

import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
@Getter
@Setter
public class TicketEntity {
    public static final String EVENT_STATUS_KEY_INDEX = "eventId-statusKey-index";

    @Getter(onMethod_ = {@DynamoDbPartitionKey, @DynamoDbSecondaryPartitionKey(indexNames = EVENT_STATUS_KEY_INDEX)})
    private String eventId;
    @Getter(onMethod_ = @DynamoDbSortKey)
    private String ticketId;
    private String status;
    private String orderId;
    private Long holdExpiresAt;
    @Getter(onMethod_ = @DynamoDbSecondarySortKey(indexNames = EVENT_STATUS_KEY_INDEX))
    private String statusKey;
    private Long version;
    private Long updatedAt;
}
