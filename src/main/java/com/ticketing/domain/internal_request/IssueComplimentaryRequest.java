package com.ticketing.domain.internal_request;

public record IssueComplimentaryRequest(String eventId, String beneficiaryId, int quantity, String issuedBy,
                                        String idempotencyKey, String requestHash, String correlationId) {
}
