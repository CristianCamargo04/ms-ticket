package com.ticketing.persistence.entity;

import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

@DynamoDbBean
@Getter
@Setter
public class EventEntity {
    public static final String STATUS_EVENT_DATE_INDEX = "status-eventDate-index";

    @Getter(onMethod_ = @DynamoDbPartitionKey)
    private String eventId;
    private String name;
    private String venue;
    @Getter(onMethod_ = @DynamoDbSecondarySortKey(indexNames = STATUS_EVENT_DATE_INDEX))
    private Long eventDate;
    private Integer totalCapacity;
    private String ticketPrice;
    private String currency;
    @Getter(onMethod_ = @DynamoDbSecondaryPartitionKey(indexNames = STATUS_EVENT_DATE_INDEX))
    private String status;
    private Integer availableCount;
    private Integer reservedCount;
    private Integer pendingCount;
    private Integer soldCount;
    private Integer complimentaryCount;
    private Long version;
    private Long createdAt;
    private Long updatedAt;
}
