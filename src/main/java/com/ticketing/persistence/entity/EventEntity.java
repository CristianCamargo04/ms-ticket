package com.ticketing.persistence.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

/**
 * Item of the {@code events} table (implementation spec 6.1).
 *
 * <p>Attributes: {@code eventId} (S, partition key), {@code name}, {@code venue}, {@code eventDate} (N, epoch millis),
 * {@code totalCapacity} (N), {@code ticketPrice} (S, plain decimal), {@code currency}, {@code status}, the inventory
 * counters {@code availableCount}, {@code reservedCount}, {@code pendingCount}, {@code soldCount},
 * {@code complimentaryCount} (N), {@code version} (N), {@code createdAt} and {@code updatedAt} (N, epoch millis).
 * The GSI {@value #STATUS_EVENT_DATE_INDEX} ({@code status}, {@code eventDate}, projection ALL) lists events by status.
 */
@DynamoDbBean
public class EventEntity {

    /** GSI listing the events of a status ordered by event date. */
    public static final String STATUS_EVENT_DATE_INDEX = "status-eventDate-index";

    private String eventId;
    private String name;
    private String venue;
    private Long eventDate;
    private Integer totalCapacity;
    private String ticketPrice;
    private String currency;
    private String status;
    private Integer availableCount;
    private Integer reservedCount;
    private Integer pendingCount;
    private Integer soldCount;
    private Integer complimentaryCount;
    private Long version;
    private Long createdAt;
    private Long updatedAt;

    @DynamoDbPartitionKey
    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVenue() {
        return venue;
    }

    public void setVenue(String venue) {
        this.venue = venue;
    }

    @DynamoDbSecondarySortKey(indexNames = STATUS_EVENT_DATE_INDEX)
    public Long getEventDate() {
        return eventDate;
    }

    public void setEventDate(Long eventDate) {
        this.eventDate = eventDate;
    }

    public Integer getTotalCapacity() {
        return totalCapacity;
    }

    public void setTotalCapacity(Integer totalCapacity) {
        this.totalCapacity = totalCapacity;
    }

    public String getTicketPrice() {
        return ticketPrice;
    }

    public void setTicketPrice(String ticketPrice) {
        this.ticketPrice = ticketPrice;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = STATUS_EVENT_DATE_INDEX)
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getAvailableCount() {
        return availableCount;
    }

    public void setAvailableCount(Integer availableCount) {
        this.availableCount = availableCount;
    }

    public Integer getReservedCount() {
        return reservedCount;
    }

    public void setReservedCount(Integer reservedCount) {
        this.reservedCount = reservedCount;
    }

    public Integer getPendingCount() {
        return pendingCount;
    }

    public void setPendingCount(Integer pendingCount) {
        this.pendingCount = pendingCount;
    }

    public Integer getSoldCount() {
        return soldCount;
    }

    public void setSoldCount(Integer soldCount) {
        this.soldCount = soldCount;
    }

    public Integer getComplimentaryCount() {
        return complimentaryCount;
    }

    public void setComplimentaryCount(Integer complimentaryCount) {
        this.complimentaryCount = complimentaryCount;
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
}
