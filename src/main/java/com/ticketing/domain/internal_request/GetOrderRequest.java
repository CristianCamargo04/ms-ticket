package com.ticketing.domain.internal_request;

/**
 * Input of the get-order-status use case.
 *
 * @param orderId     order identifier
 * @param requesterId authenticated subject
 * @param privileged  {@code true} when the requester is an administrator (may read any order)
 */
public record GetOrderRequest(String orderId, String requesterId, boolean privileged) {
}
