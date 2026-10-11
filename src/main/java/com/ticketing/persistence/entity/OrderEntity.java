package com.ticketing.persistence.entity;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

@DynamoDbBean
@Getter
@Setter
public class OrderEntity {
    public static final String DEADLINE_INDEX = "deadline-index";

    @Getter(onMethod_ = @DynamoDbPartitionKey)
    private String orderId;
    private String eventId;
    private String customerId;
    private String orderType;
    private Integer quantity;
    private List<String> ticketIds;
    private String ticketStatus;
    private String processingStatus;
    private String reason;
    private String requestHash;
    private Long holdExpiresAt;
    private String paymentReference;
    private Long version;
    private Long createdAt;
    private Long updatedAt;
    @Getter(onMethod_ = @DynamoDbSecondaryPartitionKey(indexNames = DEADLINE_INDEX))
    private String deadlinePartition;
    @Getter(onMethod_ = @DynamoDbSecondarySortKey(indexNames = DEADLINE_INDEX))
    private Long deadlineAt;
}
