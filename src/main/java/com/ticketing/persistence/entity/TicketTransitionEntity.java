package com.ticketing.persistence.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Item of the {@code ticket-transitions} audit table (implementation spec 6.1).
 *
 * <p>Attributes: {@code ticketKey} (S, partition key, {@code <eventId>#<ticketId>}), {@code transitionKey} (S, sort
 * key, {@code <epochMillis 13-digit zero-padded>#<transitionId>} so that a query returns the history in chronological
 * order), {@code eventId}, {@code ticketId}, {@code orderId}, {@code fromStatus}, {@code toStatus}, {@code reason},
 * {@code actor} and {@code occurredAt} (N, epoch millis).
 */
@DynamoDbBean
public class TicketTransitionEntity {

    private String ticketKey;
    private String transitionKey;
    private String eventId;
    private String ticketId;
    private String orderId;
    private String fromStatus;
    private String toStatus;
    private String reason;
    private String actor;
    private Long occurredAt;

    @DynamoDbPartitionKey
    public String getTicketKey() {
        return ticketKey;
    }

    public void setTicketKey(String ticketKey) {
        this.ticketKey = ticketKey;
    }

    @DynamoDbSortKey
    public String getTransitionKey() {
        return transitionKey;
    }

    public void setTransitionKey(String transitionKey) {
        this.transitionKey = transitionKey;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getTicketId() {
        return ticketId;
    }

    public void setTicketId(String ticketId) {
        this.ticketId = ticketId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public Long getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Long occurredAt) {
        this.occurredAt = occurredAt;
    }
}
