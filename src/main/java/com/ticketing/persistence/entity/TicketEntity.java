package com.ticketing.persistence.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Item of the {@code tickets} table (implementation spec 6.1).
 *
 * <p>Attributes: {@code eventId} (S, partition key), {@code ticketId} (S, sort key), {@code status},
 * {@code orderId} (only while held or final), {@code holdExpiresAt} (N, epoch millis, only while held),
 * {@code statusKey} ({@code <STATUS>#<ticketId>}), {@code version} (N) and {@code updatedAt} (N, epoch millis).
 * The GSI {@value #EVENT_STATUS_KEY_INDEX} ({@code eventId}, {@code statusKey}, INCLUDE {@code status},
 * {@code version}, {@code updatedAt}) serves the available-ticket range scans.
 */
@DynamoDbBean
public class TicketEntity {

    /** GSI used to scan the available tickets of an event in ticket id order. */
    public static final String EVENT_STATUS_KEY_INDEX = "eventId-statusKey-index";

    private String eventId;
    private String ticketId;
    private String status;
    private String orderId;
    private Long holdExpiresAt;
    private String statusKey;
    private Long version;
    private Long updatedAt;

    @DynamoDbPartitionKey
    @DynamoDbSecondaryPartitionKey(indexNames = EVENT_STATUS_KEY_INDEX)
    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    @DynamoDbSortKey
    public String getTicketId() {
        return ticketId;
    }

    public void setTicketId(String ticketId) {
        this.ticketId = ticketId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public Long getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public void setHoldExpiresAt(Long holdExpiresAt) {
        this.holdExpiresAt = holdExpiresAt;
    }

    @DynamoDbSecondarySortKey(indexNames = EVENT_STATUS_KEY_INDEX)
    public String getStatusKey() {
        return statusKey;
    }

    public void setStatusKey(String statusKey) {
        this.statusKey = statusKey;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
