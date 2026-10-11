package com.ticketing.presentation.dto;

import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.TicketStatusEnum;

public record OrderAcceptedResponseDto(String orderId, TicketStatusEnum status,
                                       OrderProcessingStatusEnum processingStatus, String statusUrl) {
}
