package com.ticketing.domain.internal_request;

import com.ticketing.enums.EventStatusEnum;

public record ListEventsRequest(EventStatusEnum status, int limit, String cursor) {
}
