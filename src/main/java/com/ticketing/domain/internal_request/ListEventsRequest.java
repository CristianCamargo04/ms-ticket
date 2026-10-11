package com.ticketing.domain.internal_request;

import com.ticketing.enums.EventStatusEnum;

/**
 * Input of the list-events use case.
 *
 * @param status status filter
 * @param limit  requested page size (clamped by the use case)
 * @param cursor opaque cursor of the requested page, {@code null} for the first page
 */
public record ListEventsRequest(EventStatusEnum status, int limit, String cursor) {
}
