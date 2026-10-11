package com.ticketing.presentation.dto;

import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.TicketStatusEnum;

/**
 * Response of an accepted (queued) order.
 *
 * @param orderId          order identifier
 * @param status           ticket status of the order
 * @param processingStatus processing status of the order
 * @param statusUrl        relative URL to poll the order status
 */
public record OrderAcceptedResponseDto(String orderId, TicketStatusEnum status,
                                       OrderProcessingStatusEnum processingStatus, String statusUrl) {
}
