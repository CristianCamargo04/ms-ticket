package com.ticketing.presentation.dto;

import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import java.time.Instant;
import java.util.List;

public record OrderStatusResponseDto(String orderId, String eventId, OrderTypeEnum type, int quantity,
                                     TicketStatusEnum status, OrderProcessingStatusEnum processingStatus,
                                     String reason, List<String> ticketIds, Instant holdExpiresAt,
                                     Instant createdAt, Instant updatedAt) {
}
