package com.ticketing.persistence.entity;

import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@DynamoDbBean
@Getter
@Setter
public class DistributedLockEntity {
    @Getter(onMethod_ = @DynamoDbPartitionKey)
    private String lockName;
    private String owner;
    private Long leaseUntil;
    private Long expiresAt;
}
