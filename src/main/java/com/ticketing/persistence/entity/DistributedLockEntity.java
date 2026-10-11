package com.ticketing.persistence.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * Item of the {@code distributed-locks} table (implementation spec 6.1).
 *
 * <p>Attributes: {@code lockName} (S, partition key), {@code owner} (S, lease holder), {@code leaseUntil} (N, epoch
 * millis, end of the lease used by the acquisition condition) and {@code expiresAt} (N, epoch <em>seconds</em>, the
 * DynamoDB TTL attribute that garbage-collects abandoned leases).
 */
@DynamoDbBean
public class DistributedLockEntity {

    private String lockName;
    private String owner;
    private Long leaseUntil;
    private Long expiresAt;

    @DynamoDbPartitionKey
    public String getLockName() {
        return lockName;
    }

    public void setLockName(String lockName) {
        this.lockName = lockName;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public Long getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Long leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }
}
