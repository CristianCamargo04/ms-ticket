package com.ticketing.presentation.dto;

import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import java.time.Instant;
import java.util.List;

/**
 * Representation of an order with its ticket and processing statuses.
 *
 * @param orderId          order identifier
 * @param eventId          event identifier
 * @param type             order type
 * @param quantity         number of tickets
 * @param status           ticket status of the order
 * @param processingStatus processing status of the order
 * @param reason           rejection/release reason, nullable
 * @param ticketIds        assigned tickets (empty until reserved)
 * @param holdExpiresAt    end of the reservation hold, nullable
 * @param createdAt        creation instant
 * @param updatedAt        last update instant
 */
public record OrderStatusResponseDto(String orderId, String eventId, OrderTypeEnum type, int quantity,
                                     TicketStatusEnum status, OrderProcessingStatusEnum processingStatus,
                                     String reason, List<String> ticketIds, Instant holdExpiresAt,
                                     Instant createdAt, Instant updatedAt) {
}
