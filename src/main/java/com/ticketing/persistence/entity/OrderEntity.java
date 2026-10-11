package com.ticketing.persistence.entity;

import java.util.List;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

/**
 * Item of the {@code orders} table (implementation spec 6.1 and 6.2).
 *
 * <p>Attributes: {@code orderId} (S, partition key), {@code eventId}, {@code customerId}, {@code orderType},
 * {@code quantity} (N), {@code ticketIds} (L of S), {@code ticketStatus}, {@code processingStatus}, {@code reason}?,
 * {@code requestHash}, {@code holdExpiresAt}? (N, epoch millis), {@code paymentReference}?, {@code version} (N),
 * {@code createdAt} and {@code updatedAt} (N, epoch millis), and the sparse deadline attributes
 * {@code deadlinePartition}? ({@code <DeadlineTypeEnum>#<shard>}) and {@code deadlineAt}? (N, epoch millis), present
 * only while the order waits for a future action. They key the sparse GSI {@value #DEADLINE_INDEX} (projection ALL).
 */
@DynamoDbBean
public class OrderEntity {

    /** Sparse GSI of the orders waiting for a deadline, sharded by {@code deadlinePartition}. */
    public static final String DEADLINE_INDEX = "deadline-index";

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
    private String deadlinePartition;
    private Long deadlineAt;

    @DynamoDbPartitionKey
    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getOrderType() {
        return orderType;
    }

    public void setOrderType(String orderType) {
        this.orderType = orderType;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public List<String> getTicketIds() {
        return ticketIds;
    }

    public void setTicketIds(List<String> ticketIds) {
        this.ticketIds = ticketIds;
    }

    public String getTicketStatus() {
        return ticketStatus;
    }

    public void setTicketStatus(String ticketStatus) {
        this.ticketStatus = ticketStatus;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public Long getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public void setHoldExpiresAt(Long holdExpiresAt) {
        this.holdExpiresAt = holdExpiresAt;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = DEADLINE_INDEX)
    public String getDeadlinePartition() {
        return deadlinePartition;
    }

    public void setDeadlinePartition(String deadlinePartition) {
        this.deadlinePartition = deadlinePartition;
    }

    @DynamoDbSecondarySortKey(indexNames = DEADLINE_INDEX)
    public Long getDeadlineAt() {
        return deadlineAt;
    }

    public void setDeadlineAt(Long deadlineAt) {
        this.deadlineAt = deadlineAt;
    }
}
