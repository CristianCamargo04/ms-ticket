package com.ticketing.domain.internal_request;

public record GetOrderRequest(String orderId, String requesterId, boolean privileged) {
}
